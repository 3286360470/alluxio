/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.rest;

import static org.junit.Assert.assertEquals;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.URIStatus;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.exception.FileDoesNotExistException;
import alluxio.grpc.ListStatusPOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.master.file.FileSystemMaster;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.GetStatusContext;
import alluxio.master.file.contexts.ListStatusContext;
import alluxio.proxy.s3.CompleteMultipartUploadResult;
import alluxio.proxy.s3.InitiateMultipartUploadResult;
import alluxio.proxy.s3.ListAllMyBucketsResult;
import alluxio.proxy.s3.ListBucketOptions;
import alluxio.proxy.s3.ListBucketResult;
import alluxio.proxy.s3.ListPartsResult;
import alluxio.proxy.s3.S3Constants;
import alluxio.proxy.s3.S3RestServiceHandler;
import alluxio.proxy.s3.S3RestUtils;
import alluxio.proxy.s3.TaggingData;
import alluxio.security.User;
import alluxio.security.authentication.AuthType;
import alluxio.security.authorization.Mode;
import alluxio.security.authorization.ModeParser;
import alluxio.testutils.LocalAlluxioClusterResource;
import alluxio.util.CommonUtils;
import alluxio.wire.FileInfo;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;

import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response;

/**
 * Test cases for {@link alluxio.proxy.s3.S3RestServiceHandler}.
 */
public final class S3ClientRestApiTest extends RestApiTest {
  private static final int DATA_SIZE = 16 * Constants.KB;
  // cannot be too large, since all block streams are open until file is closed, and may run out of
  // block worker clients.
  private static final int LARGE_DATA_SIZE = 256 * Constants.KB;

  private static final GetStatusContext GET_STATUS_CONTEXT = GetStatusContext.defaults();
  private static final XmlMapper XML_MAPPER = new XmlMapper();

  private FileSystem mFileSystem;
  private FileSystemMaster mFileSystemMaster;

  // TODO(chaomin): Rest API integration tests are only run in NOSASL mode now. Need to
  // fix the test setup in SIMPLE mode.
  @ClassRule
  public static LocalAlluxioClusterResource sResource = new LocalAlluxioClusterResource.Builder()
      .setIncludeProxy(true)
      .setProperty(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_ENABLED, false)
      .setProperty(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.NOSASL)
      .setProperty(PropertyKey.USER_FILE_BUFFER_BYTES, "1KB")
      .build();

  @Rule
  public TestRule mResetRule = sResource.getResetResource();

  @Rule
  public TemporaryFolder mFolder = new TemporaryFolder();

  @Before
  public void before() throws Exception {
    mHostname = sResource.get().getHostname();
    mPort = sResource.get().getProxyProcess().getWebLocalPort();
    mFileSystemMaster = sResource.get().getLocalAlluxioMaster().getMasterProcess()
        .getMaster(FileSystemMaster.class);
    mFileSystem = sResource.get().getClient();
    mBaseUri = String.format("%s/%s", mBaseUri, S3RestServiceHandler.SERVICE_PREFIX);
  }

  @Test
  public void listAllMyBuckets() throws Exception {
    Mode mode = ModeParser.parse("777");
    SetAttributePOptions options =
        SetAttributePOptions.newBuilder().setMode(mode.toProto()).setRecursive(true).build();
    mFileSystem.setAttribute(new AlluxioURI("/"), options);

    Subject subject = new Subject();
    subject.getPrincipals().add(new User("user0"));
    AlluxioURI bucketPath = new AlluxioURI("/bucket0");
    FileSystem fs1 = sResource.get().getClient(FileSystemContext.create(subject,
            ServerConfiguration.global()));
    fs1.createDirectory(bucketPath);
    SetAttributePOptions setAttributeOptions =
        SetAttributePOptions.newBuilder().setOwner("user0").build();
    mFileSystem.setAttribute(new AlluxioURI("/bucket0"), setAttributeOptions);
    URIStatus bucket0Status = fs1.getStatus(bucketPath);

    subject = new Subject();
    subject.getPrincipals().add(new User("user1"));
    AlluxioURI bucket1Path = new AlluxioURI("/bucket1");
    FileSystem fs2 = sResource.get().getClient(FileSystemContext.create(subject,
            ServerConfiguration.global()));
    fs2.createDirectory(bucket1Path);
    setAttributeOptions = SetAttributePOptions.newBuilder().setOwner("user1").build();
    mFileSystem.setAttribute(new AlluxioURI("/bucket1"), setAttributeOptions);
    URIStatus bucket1Status = fs2.getStatus(bucket1Path);

    ListAllMyBucketsResult expected = new ListAllMyBucketsResult(Collections.emptyList());
    final TestCaseOptions requestOptions = TestCaseOptions.defaults()
        .setContentType(TestCaseOptions.XML_CONTENT_TYPE);
    new TestCase(mHostname, mPort, mBaseUri,
        "", NO_PARAMS, HttpMethod.GET,
        requestOptions).runAndCheckResult(expected);

    expected = new ListAllMyBucketsResult(Lists.newArrayList(bucket0Status));
    requestOptions.setAuthorization("AWS4-HMAC-SHA256 Credential=user0/20210631");
    new TestCase(mHostname, mPort, mBaseUri,
        "", NO_PARAMS, HttpMethod.GET,
        requestOptions).runAndCheckResult(expected);

    expected = new ListAllMyBucketsResult(Lists.newArrayList(bucket1Status));
    requestOptions.setAuthorization("AWS4-HMAC-SHA256 Credential=user1/20210631");
    new TestCase(mHostname, mPort, mBaseUri,
        "", NO_PARAMS, HttpMethod.GET,
        requestOptions).runAndCheckResult(expected);
  }

  @Test
  public void listBucket() throws Exception {
    mFileSystem.createDirectory(new AlluxioURI("/bucket"));
    mFileSystem.createDirectory(new AlluxioURI("/bucket/folder0"));
    mFileSystem.createDirectory(new AlluxioURI("/bucket/folder1"));

    mFileSystem.createFile(new AlluxioURI("/bucket/file0"));
    mFileSystem.createFile(new AlluxioURI("/bucket/file1"));

    mFileSystem.createFile(new AlluxioURI("/bucket/folder0/file0"));
    mFileSystem.createFile(new AlluxioURI("/bucket/folder0/file1"));

    //empty parameters
    ListStatusPOptions options  = ListStatusPOptions.newBuilder().setRecursive(true).build();
    List<URIStatus> statuses = mFileSystem.listStatus(new AlluxioURI("/bucket"), options);

    ListBucketResult expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults());

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", NO_PARAMS, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals(6, expected.getContents().size());
    assertEquals("file0", expected.getContents().get(0).getKey());
    assertEquals("file1", expected.getContents().get(1).getKey());
    assertEquals("folder0/", expected.getContents().get(2).getKey());
    assertEquals("folder0/file0", expected.getContents().get(3).getKey());
    assertEquals("folder0/file1", expected.getContents().get(4).getKey());
    assertEquals("folder1/", expected.getContents().get(5).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    //parameters with delimiter="/"
    statuses = mFileSystem.listStatus(new AlluxioURI("/bucket"));

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setDelimiter(AlluxioURI.SEPARATOR));

    Map<String, String> parameters = new HashMap<>();
    parameters.put("delimiter", AlluxioURI.SEPARATOR);

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals(2, expected.getContents().size());
    assertEquals("file0", expected.getContents().get(0).getKey());
    assertEquals("file1", expected.getContents().get(1).getKey());
    assertEquals(2, expected.getCommonPrefixes().size());
    assertEquals("folder0/", expected.getCommonPrefixes().get(0).getPrefix());
    assertEquals("folder1/", expected.getCommonPrefixes().get(1).getPrefix());

    //parameters with prefix="folder0"
    statuses = mFileSystem.listStatus(new AlluxioURI("/bucket"), options);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setPrefix("folder0"));

    parameters.clear();
    parameters.put("prefix", "folder0");

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals(3, expected.getContents().size());
    assertEquals("folder0/", expected.getContents().get(0).getKey());
    assertEquals("folder0/file0", expected.getContents().get(1).getKey());
    assertEquals("folder0/file1", expected.getContents().get(2).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    //parameters with list-type=2 start-after="folder0/file0"
    statuses = mFileSystem.listStatus(new AlluxioURI("/bucket"), options);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setListType(2).setStartAfter("file0"));

    parameters.clear();
    parameters.put("list-type", "2");
    parameters.put("start-after", "file0");

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals(5, expected.getContents().size());
    assertEquals("file1", expected.getContents().get(0).getKey());
    assertEquals("folder0/", expected.getContents().get(1).getKey());
    assertEquals("folder0/file0", expected.getContents().get(2).getKey());
    assertEquals("folder0/file1", expected.getContents().get(3).getKey());
    assertEquals("folder1/", expected.getContents().get(4).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());
  }

  @Test
  public void listBucketPagination() throws Exception {
    AlluxioURI uri = new AlluxioURI("/bucket");
    mFileSystem.createDirectory(uri);
    mFileSystem.createDirectory(new AlluxioURI("/bucket/folder0"));
    mFileSystem.createDirectory(new AlluxioURI("/bucket/folder1"));

    mFileSystem.createFile(new AlluxioURI("/bucket/file0"));
    mFileSystem.createFile(new AlluxioURI("/bucket/file1"));

    mFileSystem.createFile(new AlluxioURI("/bucket/folder0/file0"));
    mFileSystem.createFile(new AlluxioURI("/bucket/folder0/file1"));

    ListStatusPOptions options  = ListStatusPOptions.newBuilder().setRecursive(true).build();
    List<URIStatus> statuses = mFileSystem.listStatus(new AlluxioURI("/bucket"), options);

    //parameters with max-keys=1
    ListBucketResult expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1));
    String nextMarker = expected.getNextMarker();
    assertEquals("/bucket/file0", nextMarker);

    final Map<String, String> parameters = new HashMap<>();
    parameters.put("max-keys", "1");

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("file0", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    parameters.put("marker", nextMarker);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1).setMarker(nextMarker));
    nextMarker = expected.getNextMarker();

    assertEquals(1, expected.getContents().size());

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("file1", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    parameters.put("marker", nextMarker);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1).setMarker(nextMarker));
    nextMarker = expected.getNextMarker();

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("folder0/", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    //parameters with list-type=2 and max-key=1
    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1).setListType(2));
    String nextContinuationToken = expected.getNextContinuationToken();
    assertEquals(ListBucketResult.encodeToken("/bucket/file0"), nextContinuationToken);

    parameters.remove("marker");
    parameters.put("list-type", "2");

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("file0", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    parameters.put("continuation-token", nextContinuationToken);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1)
            .setListType(2).setContinuationToken(nextContinuationToken));
    nextContinuationToken = expected.getNextContinuationToken();
    assertEquals(ListBucketResult.encodeToken("/bucket/file1"), nextContinuationToken);

    assertEquals(1, expected.getContents().size());

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("file1", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());

    parameters.put("continuation-token", nextContinuationToken);

    expected = new ListBucketResult("bucket", statuses,
        ListBucketOptions.defaults().setMaxKeys(1)
            .setListType(2).setContinuationToken(nextContinuationToken));
    nextContinuationToken = expected.getNextContinuationToken();
    assertEquals(ListBucketResult.encodeToken("/bucket/folder0"), nextContinuationToken);

    new TestCase(mHostname, mPort, mBaseUri,
        "bucket", parameters, HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
        .runAndCheckResult(expected);

    assertEquals("folder0/", expected.getContents().get(0).getKey());
    assertEquals(0, expected.getCommonPrefixes().size());
  }

  @Test
  public void putBucket() throws Exception {
    final String bucket = "bucket";
    createBucketRestCall(bucket);
    // Verify the directory is created for the new bucket.
    AlluxioURI uri = new AlluxioURI(AlluxioURI.SEPARATOR + bucket);
    Assert.assertTrue(mFileSystemMaster
        .listStatus(uri, ListStatusContext.defaults()).isEmpty());
  }

  @Test
  public void deleteBucket() throws Exception {
    final String bucket = "bucket-to-delete";
    createBucketRestCall(bucket);

    // Verify the directory is created for the new bucket.
    AlluxioURI uri = new AlluxioURI(AlluxioURI.SEPARATOR + bucket);
    Assert.assertTrue(mFileSystemMaster
        .listStatus(uri, ListStatusContext.defaults()).isEmpty());

    HttpURLConnection connection = deleteBucketRestCall(bucket);
    Assert.assertEquals(Response.Status.NO_CONTENT.getStatusCode(), connection.getResponseCode());

    try {
      mFileSystemMaster.getFileInfo(uri, GET_STATUS_CONTEXT);
    } catch (FileDoesNotExistException e) {
      // expected
      return;
    }
    Assert.fail("bucket should have been removed");
  }

  @Test
  public void deleteNonExistingBucket() throws Exception {
    final String bucketName = "non-existing-bucket";

    try {
      // Delete a non-existing bucket should fail.
      deleteBucketRestCall(bucketName);
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("delete a non-existing bucket should fail");
  }

  @Test
  public void getNonExistingBucket() throws Exception {
    final String bucketName = "root-level-file";
    mFileSystem.createFile(new AlluxioURI("/" + bucketName));

    try {
      // GET on a non-existing bucket should fail.
      new TestCase(mHostname, mPort, mBaseUri,
          bucketName, NO_PARAMS, HttpMethod.GET,
          TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE))
          .runAndGetResponse();
    } catch (AssertionError e) {
      return; // expected
    }
    Assert.fail("GET on a non-existing bucket should fail");
  }

  @Test
  public void deleteNonEmptyBucket() throws Exception {
    final String bucketName = "non-empty-bucket";

    createBucketRestCall(bucketName);

    AlluxioURI uri = new AlluxioURI(AlluxioURI.SEPARATOR + bucketName);
    AlluxioURI fileUri = new AlluxioURI(uri.getPath() + "/file");
    mFileSystemMaster.createFile(fileUri, CreateFileContext.defaults());

    // Verify the directory is created for the new bucket, and file is created under it.
    Assert.assertFalse(mFileSystemMaster
        .listStatus(uri, ListStatusContext.defaults()).isEmpty());

    try {
      // Delete a non-empty bucket should fail.
      deleteBucketRestCall(bucketName);
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("delete a non-empty bucket should fail");
  }

  private void createObject(String objectKey, byte[] object, Long uploadId,
      Integer partNumber) throws Exception {
    Map<String, String> params = new HashMap<>();
    if (uploadId != null) {
      params.put("uploadId", uploadId.toString());
    }
    if (partNumber != null) {
      params.put("partNumber", partNumber.toString());
    }
    createObjectRestCall(objectKey, params,
        TestCaseOptions.defaults()
            .setBody(object)
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(object)));
  }

  private void putObjectTest(String bucket, String objectKey, byte[] object, Long uploadId,
      Integer partNumber) throws Exception {
    final String fullObjectKey = bucket + AlluxioURI.SEPARATOR + objectKey;
    createObject(fullObjectKey, object, uploadId, partNumber);

    // Verify the object is created for the new bucket.
    AlluxioURI bucketURI = new AlluxioURI(AlluxioURI.SEPARATOR + bucket);
    AlluxioURI objectURI = new AlluxioURI(AlluxioURI.SEPARATOR + fullObjectKey);
    if (uploadId != null) {
      String tmpDir = S3RestUtils.getMultipartTemporaryDirForObject(bucketURI.getPath(), objectKey);
      bucketURI = new AlluxioURI(tmpDir);
      objectURI = new AlluxioURI(tmpDir + AlluxioURI.SEPARATOR + partNumber.toString());
    }
    List<FileInfo> fileInfos =
        mFileSystemMaster.listStatus(bucketURI, ListStatusContext.defaults());
    Assert.assertEquals(1, fileInfos.size());
    Assert.assertEquals(objectURI.getPath(), fileInfos.get(0).getPath());

    // Verify the object's content.
    FileInStream is = mFileSystem.openFile(objectURI);
    byte[] writtenObjectContent = IOUtils.toString(is).getBytes();
    is.close();
    Assert.assertArrayEquals(object, writtenObjectContent);
  }

  @Test
  public void putDirectoryObject() throws Exception {
    final String bucketName = "directory-bucket";
    createBucketRestCall(bucketName);

    final String directoryName = "directory/";
    createObject(bucketName + AlluxioURI.SEPARATOR + directoryName, new byte[]{}, null, null);

    final List<URIStatus> statuses = mFileSystem.listStatus(
        new AlluxioURI(AlluxioURI.SEPARATOR + bucketName));

    assertEquals(1, statuses.size());
    assertEquals(true, statuses.get(0).isFolder());
  }

  @Test
  public void putSmallObject() throws Exception {
    final String bucketName = "small-object-bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    putObjectTest(bucketName, objectName, "Hello World!".getBytes(), null, null);
  }

  @Test
  public void putLargeObject() throws Exception {
    final String bucketName = "large-object-bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    final byte[] object = CommonUtils.randomAlphaNumString(LARGE_DATA_SIZE).getBytes();
    putObjectTest(bucketName, objectName, object, null, null);
  }

  @Test
  public void putObjectUnderNonExistentBucket() throws Exception {
    final String bucket = "non-existent-bucket";

    final String objectKey = bucket + AlluxioURI.SEPARATOR + "object.txt";
    String message = "hello world";
    try {
      createObjectRestCall(objectKey, NO_PARAMS,
          TestCaseOptions.defaults()
              .setBody(message.getBytes())
              .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
              .setMD5(computeObjectChecksum(message.getBytes())));
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("create object under non-existent bucket should fail");
  }

  @Test
  public void putObjectWithWrongMD5() throws Exception {
    final String bucket = "bucket";
    createBucketRestCall(bucket);

    final String objectKey = bucket + AlluxioURI.SEPARATOR + "object.txt";
    String objectContent = "hello world";
    try {
      String wrongMD5 = BaseEncoding.base64().encode(objectContent.getBytes());
      createObjectRestCall(objectKey, NO_PARAMS,
          TestCaseOptions.defaults()
              .setBody(objectContent.getBytes())
              .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
              .setMD5(wrongMD5));
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("create object with wrong Content-MD5 should fail");
  }

  @Test
  public void putObjectWithNoMD5() throws Exception {
    final String bucket = "bucket";
    createBucketRestCall(bucket);

    final String objectKey = bucket + AlluxioURI.SEPARATOR + "object.txt";
    String objectContent = "no md5 set";
    TestCaseOptions options = TestCaseOptions.defaults();
    options.setBody(objectContent.getBytes());
    options.setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE);
    new TestCase(mHostname, mPort, mBaseUri,
        objectKey, NO_PARAMS, HttpMethod.PUT,
        options).runAndCheckResult();
  }

  private void getObjectTest(byte[] expectedObject) throws Exception {
    final String bucket = "bucket";
    createBucketRestCall(bucket);
    final String objectKey = bucket + AlluxioURI.SEPARATOR + "object.txt";
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(expectedObject)
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(expectedObject)));
    Assert.assertArrayEquals(expectedObject, getObjectRestCall(objectKey).getBytes());
  }

  @Test
  public void getSmallObject() throws Exception {
    getObjectTest("Hello World!".getBytes());
  }

  @Test
  public void getLargeObject() throws Exception {
    getObjectTest(CommonUtils.randomAlphaNumString(LARGE_DATA_SIZE).getBytes());
  }

  @Test
  public void getNonExistentObject() throws Exception {
    final String objectKey = "bucket/non-existent-object";
    try {
      getObjectRestCall(objectKey);
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("get non-existent object should fail");
  }

  @Test
  public void getObjectMetadata() throws Exception {
    final String bucket = "bucket";
    createBucketRestCall(bucket);

    final String objectKey = bucket + AlluxioURI.SEPARATOR + "object.txt";
    final byte[] objectContent = CommonUtils.randomAlphaNumString(10).getBytes();
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(objectContent)
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(objectContent)));

    HttpURLConnection connection = getObjectMetadataRestCall(objectKey);
    URIStatus status = mFileSystem.getStatus(
        new AlluxioURI(AlluxioURI.SEPARATOR + objectKey));
    // remove the milliseconds from the last modification time because the accuracy of HTTP dates
    // is up to seconds.
    long lastModified = status.getLastModificationTimeMs() / 1000 * 1000;
    Assert.assertEquals(lastModified, connection.getLastModified());
    Assert.assertEquals(String.valueOf(status.getLength()),
        connection.getHeaderField(S3Constants.S3_CONTENT_LENGTH_HEADER));
  }

  @Test
  public void getNonExistentObjectMetadata() throws Exception {
    final String objectKey = "bucket/non-existent-object";
    try {
      getObjectMetadataRestCall(objectKey);
    } catch (AssertionError e) {
      // expected
      return;
    }
    Assert.fail("get metadata of non-existent object should fail");
  }

  @Test
  public void deleteObject() throws Exception {
    final String bucketName = "bucket-with-object-to-delete";
    createBucketRestCall(bucketName);

    final String objectName = "file";
    AlluxioURI bucketUri = new AlluxioURI(AlluxioURI.SEPARATOR + bucketName);
    AlluxioURI fileUri = new AlluxioURI(
        bucketUri.getPath() + AlluxioURI.SEPARATOR + objectName);
    mFileSystemMaster.createFile(fileUri, CreateFileContext.defaults());

    // Verify the directory is created for the new bucket, and file is created under it.
    Assert.assertFalse(mFileSystemMaster
        .listStatus(bucketUri, ListStatusContext.defaults()).isEmpty());

    deleteObjectRestCall(bucketName + AlluxioURI.SEPARATOR + objectName);

    // Verify the object is deleted.
    Assert.assertTrue(mFileSystemMaster
        .listStatus(bucketUri, ListStatusContext.defaults()).isEmpty());
  }

  @Test
  public void deleteObjectAsAlluxioEmptyDir() throws Exception {
    final String bucketName = "bucket-with-empty-dir-to-delete";
    createBucketRestCall(bucketName);

    String objectName = "empty-dir/";
    AlluxioURI bucketUri = new AlluxioURI(AlluxioURI.SEPARATOR + bucketName);
    AlluxioURI dirUri = new AlluxioURI(
        bucketUri.getPath() + AlluxioURI.SEPARATOR + objectName);
    mFileSystemMaster.createDirectory(dirUri, CreateDirectoryContext.defaults());

    // Verify the directory is created for the new bucket, and empty-dir is created under it.
    Assert.assertFalse(mFileSystemMaster
        .listStatus(bucketUri, ListStatusContext.defaults()).isEmpty());

    deleteObjectRestCall(bucketName + AlluxioURI.SEPARATOR + objectName);

    // Verify the empty-dir as a valid object is deleted.
    Assert.assertTrue(mFileSystemMaster
        .listStatus(bucketUri, ListStatusContext.defaults()).isEmpty());
  }

  @Test
  public void deleteObjectAsAlluxioNonEmptyDir() throws Exception {
    final String bucketName = "bucket-with-non-empty-dir-to-delete";
    createBucketRestCall(bucketName);

    String objectName = "non-empty-dir/";
    AlluxioURI bucketUri = new AlluxioURI(AlluxioURI.SEPARATOR + bucketName);
    AlluxioURI dirUri = new AlluxioURI(
        bucketUri.getPath() + AlluxioURI.SEPARATOR + objectName);
    mFileSystemMaster.createDirectory(dirUri, CreateDirectoryContext.defaults());

    mFileSystemMaster.createFile(
        new AlluxioURI(dirUri.getPath() + "/file"), CreateFileContext.defaults());

    Assert.assertFalse(mFileSystemMaster
        .listStatus(dirUri, ListStatusContext.defaults()).isEmpty());

    deleteObjectRestCall(bucketName + AlluxioURI.SEPARATOR + objectName);
  }

  @Test
  public void deleteNonExistingObject() throws Exception {
    final String bucketName = "bucket-with-nothing";
    createBucketRestCall(bucketName);

    String objectName = "non-existing-object";
    deleteObjectRestCall(bucketName + AlluxioURI.SEPARATOR + objectName);
  }

  @Test
  public void initiateMultipartUpload() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String result = initiateMultipartUploadRestCall(objectKey);

    String multipartTempDir = S3RestUtils.getMultipartTemporaryDirForObject(
        AlluxioURI.SEPARATOR + bucketName, objectName);
    URIStatus status = mFileSystem.getStatus(new AlluxioURI(multipartTempDir));
    long tempDirId = status.getFileId();
    InitiateMultipartUploadResult expected =
        new InitiateMultipartUploadResult(bucketName, objectName, Long.toString(tempDirId));
    String expectedResult = XML_MAPPER.writeValueAsString(expected);

    Assert.assertEquals(expectedResult, result);
  }

  @Test
  public void uploadPart() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);

    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());
    final byte[] object = CommonUtils.randomAlphaNumString(DATA_SIZE).getBytes();
    putObjectTest(bucketName, objectName, object, uploadId, 1);
  }

  @Test
  public void uploadPartWithNonExistingUploadId() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);

    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());
    final byte[] object = CommonUtils.randomAlphaNumString(DATA_SIZE).getBytes();
    try {
      putObjectTest(bucketName, objectName, object, uploadId + 1, 1);
    } catch (AssertionError e) {
      // Expected because of the wrong upload ID.
      return;
    }
    Assert.fail("Upload part of an object with wrong upload ID should fail");
  }

  @Test
  public void uploadPartWithoutInitiation() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    try {
      final String objectName = "object";
      final byte[] object = CommonUtils.randomAlphaNumString(DATA_SIZE).getBytes();
      putObjectTest(bucketName, objectName, object, 1L, 1);
    } catch (AssertionError e) {
      // Expected because there is no such upload ID.
      return;
    }
    Assert.fail("Upload part of an object without multipart upload initialization should fail");
  }

  @Test
  public void listParts() throws Exception {
    final String bucket = "bucket";
    final String bucketPath = AlluxioURI.SEPARATOR + bucket;
    createBucketRestCall(bucket);

    final String object = "object";
    final String objectKey = bucket + AlluxioURI.SEPARATOR + object;

    // Initiate multipart upload to get upload ID.
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);
    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());

    // No parts are uploaded yet.
    result = listPartsRestCall(objectKey, uploadId);
    ListPartsResult listPartsResult = XML_MAPPER.readValue(result, ListPartsResult.class);
    Assert.assertEquals(bucketPath, listPartsResult.getBucket());
    Assert.assertEquals(object, listPartsResult.getKey());
    Assert.assertEquals(Long.toString(uploadId), listPartsResult.getUploadId());
    Assert.assertEquals(0, listPartsResult.getParts().size());

    // Upload 2 parts.
    String object1 = CommonUtils.randomAlphaNumString(DATA_SIZE);
    String object2 = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObject(objectKey, object1.getBytes(), uploadId, 1);
    createObject(objectKey, object2.getBytes(), uploadId, 2);

    result = listPartsRestCall(objectKey, uploadId);
    listPartsResult = XML_MAPPER.readValue(result, ListPartsResult.class);
    Assert.assertEquals(bucketPath, listPartsResult.getBucket());
    Assert.assertEquals(object, listPartsResult.getKey());
    Assert.assertEquals(Long.toString(uploadId), listPartsResult.getUploadId());

    String tmpDir = S3RestUtils.getMultipartTemporaryDirForObject(bucketPath, object);
    List<ListPartsResult.Part> parts = listPartsResult.getParts();
    Assert.assertEquals(2, parts.size());
    for (int partNumber = 1; partNumber <= parts.size(); partNumber++) {
      ListPartsResult.Part part = parts.get(partNumber - 1);
      Assert.assertEquals(partNumber, part.getPartNumber());
      URIStatus status = mFileSystem.getStatus(
          new AlluxioURI(tmpDir + AlluxioURI.SEPARATOR + Integer.toString(partNumber)));
      Assert.assertEquals(S3RestUtils.toS3Date(status.getLastModificationTimeMs()),
          part.getLastModified());
      Assert.assertEquals(status.getLength(), part.getSize());
    }
  }

  @Test
  public void abortMultipartUpload() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);
    AlluxioURI tmpDir = new AlluxioURI(S3RestUtils.getMultipartTemporaryDirForObject(
        AlluxioURI.SEPARATOR + bucketName, objectName));
    Assert.assertTrue(mFileSystem.exists(tmpDir));
    Assert.assertTrue(mFileSystem.getStatus(tmpDir).isFolder());

    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());
    HttpURLConnection connection = abortMultipartUploadRestCall(objectKey, uploadId);
    Assert.assertEquals(Response.Status.NO_CONTENT.getStatusCode(), connection.getResponseCode());
    Assert.assertFalse(mFileSystem.exists(tmpDir));
  }

  @Test
  public void abortMultipartUploadWithNonExistingUploadId() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);
    AlluxioURI tmpDir = new AlluxioURI(S3RestUtils.getMultipartTemporaryDirForObject(
        AlluxioURI.SEPARATOR + bucketName, objectName));
    Assert.assertTrue(mFileSystem.exists(tmpDir));
    Assert.assertTrue(mFileSystem.getStatus(tmpDir).isFolder());

    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());
    try {
      abortMultipartUploadRestCall(objectKey, uploadId + 1);
    } catch (AssertionError e) {
      // Expected since the upload ID does not exist, the temporary directory should still exist.
      Assert.assertTrue(mFileSystem.exists(tmpDir));
      return;
    }
    Assert.fail("Abort multipart upload with non-existing upload ID should fail");
  }

  @Test
  public void completeMultipartUpload() throws Exception {
    // Two temporary parts in the multipart upload, each part contains a random string,
    // after completion, the object should contain the combination of the two strings.

    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;

    // Initiate the multipart upload.
    String result = initiateMultipartUploadRestCall(objectKey);
    InitiateMultipartUploadResult multipartUploadResult =
        XML_MAPPER.readValue(result, InitiateMultipartUploadResult.class);
    final long uploadId = Long.parseLong(multipartUploadResult.getUploadId());

    // Upload parts.
    String object1 = CommonUtils.randomAlphaNumString(DATA_SIZE);
    String object2 = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObject(objectKey, object1.getBytes(), uploadId, 1);
    createObject(objectKey, object2.getBytes(), uploadId, 2);

    // Verify that the two parts are uploaded to the temporary directory.
    AlluxioURI tmpDir = new AlluxioURI(S3RestUtils.getMultipartTemporaryDirForObject(
        AlluxioURI.SEPARATOR + bucketName, objectName));
    Assert.assertEquals(2, mFileSystem.listStatus(tmpDir).size());

    // Complete the multipart upload.
    result = completeMultipartUploadRestCall(objectKey, uploadId);

    // Verify that the response is expected.
    String expectedCombinedObject = object1 + object2;
    MessageDigest md5 = MessageDigest.getInstance("MD5");
    byte[] digest = md5.digest(expectedCombinedObject.getBytes());
    String etag = Hex.encodeHexString(digest);
    String objectPath = AlluxioURI.SEPARATOR + objectKey;
    CompleteMultipartUploadResult completeMultipartUploadResult =
        new CompleteMultipartUploadResult(objectPath, bucketName, objectName, etag);
    Assert.assertEquals(XML_MAPPER.writeValueAsString(completeMultipartUploadResult),
        result.trim());
    Assert.assertEquals(XML_MAPPER.readValue(result, CompleteMultipartUploadResult.class),
        completeMultipartUploadResult);

    // Verify that the temporary directory is deleted.
    Assert.assertFalse(mFileSystem.exists(tmpDir));

    // Verify that the completed object is expected.
    try (FileInStream is = mFileSystem.openFile(new AlluxioURI(objectPath))) {
      String combinedObject = IOUtils.toString(is);
      Assert.assertEquals(expectedCombinedObject, combinedObject);
    }
  }

  @Test
  public void testObjectContentType() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String objectData = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(objectData)
            .setContentType(TestCaseOptions.TEXT_PLAIN_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(objectData.getBytes())));

    HttpURLConnection connection = getObjectMetadataRestCall(objectKey);
    Assert.assertEquals(TestCaseOptions.TEXT_PLAIN_CONTENT_TYPE,
        connection.getHeaderField(TestCaseOptions.CONTENT_TYPE_HEADER));
  }

  @Test
  public void testBucketTagging() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);
    testTagging(bucketName, ImmutableMap.of());
  }

  @Test
  public void testObjectTagsHeader() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String objectData = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(objectData.getBytes())
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(objectData.getBytes()))
            .addHeader(S3Constants.S3_TAGGING_HEADER, "foo=bar&baz"));

    testTagging(objectKey, ImmutableMap.of(
        "foo", "bar",
        "baz", ""
    ));
  }

  @Test
  public void testObjectTagging() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String objectName = "object";
    String objectKey = bucketName + AlluxioURI.SEPARATOR + objectName;
    String objectData = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(objectData.getBytes())
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(objectData.getBytes())));

    testTagging(objectKey, ImmutableMap.of());
  }

  @Test
  public void testFolderTagging() throws Exception {
    final String bucketName = "bucket";
    createBucketRestCall(bucketName);

    final String folderName = "folder";
    String folderKey = bucketName + AlluxioURI.SEPARATOR + folderName;
    final String objectName = "object";
    String objectKey = folderKey + AlluxioURI.SEPARATOR + objectName;
    String objectData = CommonUtils.randomAlphaNumString(DATA_SIZE);
    createObjectRestCall(objectKey, NO_PARAMS,
        TestCaseOptions.defaults()
            .setBody(objectData.getBytes())
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setMD5(computeObjectChecksum(objectData.getBytes()))
            .addHeader(S3Constants.S3_TAGGING_HEADER, "foo=bar"));

    // Ensure that folders are not populated with tags from children
    testTagging(folderKey, ImmutableMap.of());
  }

  private void testTagging(String resource, ImmutableMap<String, String> expectedTags)
      throws Exception {
    // Get{...}Tagging
    TaggingData tagData = getTagsRestCall(resource);
    if (expectedTags != null) { // allow skipping checking of initial tags
      Assert.assertEquals(expectedTags, tagData.getTagMap());
    }

    // Put{...}Tagging
    Map<String, String> tagMap = ImmutableMap.of(
        "foo", "bar",
        "fu", "bar",
        "baz", ""
    );
    tagData.addTags(tagMap);
    putTagsRestCall(resource, tagData);

    // Get{...}Tagging
    TaggingData newTags = getTagsRestCall(resource);
    Assert.assertEquals(tagMap, newTags.getTagMap());

    // Delete{...}Tagging
    deleteTagsRestCall(resource);
    TaggingData deletedTags = getTagsRestCall(resource);
    Assert.assertEquals(0, deletedTags.getTagMap().size());
  }

  private void createBucketRestCall(String bucketUri) throws Exception {
    new TestCase(mHostname, mPort, mBaseUri,
        bucketUri, NO_PARAMS, HttpMethod.PUT,
        TestCaseOptions.defaults()).runAndCheckResult();
  }

  private HttpURLConnection deleteBucketRestCall(String bucketUri) throws Exception {
    return new TestCase(mHostname, mPort, mBaseUri,
        bucketUri, NO_PARAMS, HttpMethod.DELETE,
        TestCaseOptions.defaults()).execute();
  }

  private String computeObjectChecksum(byte[] objectContent) throws Exception {
    MessageDigest md5Hash = MessageDigest.getInstance("MD5");
    byte[] md5Digest = md5Hash.digest(objectContent);
    return BaseEncoding.base64().encode(md5Digest);
  }

  private void createObjectRestCall(String objectUri, @NotNull Map<String, String> params,
                                    @NotNull TestCaseOptions options) throws Exception {
    new TestCase(mHostname, mPort, mBaseUri, objectUri, params, HttpMethod.PUT, options)
        .runAndCheckResult();
  }

  private String initiateMultipartUploadRestCall(String objectUri) throws Exception {
    Map<String, String> params = ImmutableMap.of("uploads", "");
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, params, HttpMethod.POST,
        TestCaseOptions.defaults()).runAndGetResponse();
  }

  private String completeMultipartUploadRestCall(String objectUri, long uploadId) throws Exception {
    Map<String, String> params = ImmutableMap.of("uploadId", Long.toString(uploadId));
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, params, HttpMethod.POST,
        TestCaseOptions.defaults()).runAndGetResponse();
  }

  private HttpURLConnection abortMultipartUploadRestCall(String objectUri, long uploadId)
      throws Exception {
    Map<String, String> params = ImmutableMap.of("uploadId", Long.toString(uploadId));
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, params, HttpMethod.DELETE,
        TestCaseOptions.defaults()).execute();
  }

  private String listPartsRestCall(String objectUri, long uploadId)
      throws Exception {
    Map<String, String> params = ImmutableMap.of("uploadId", Long.toString(uploadId));
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, params, HttpMethod.GET,
        TestCaseOptions.defaults()).runAndGetResponse();
  }

  private HttpURLConnection getObjectMetadataRestCall(String objectUri) throws Exception {
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, NO_PARAMS, HttpMethod.HEAD,
        TestCaseOptions.defaults()).execute();
  }

  private String getObjectRestCall(String objectUri) throws Exception {
    return new TestCase(mHostname, mPort, mBaseUri,
        objectUri, NO_PARAMS, HttpMethod.GET,
        TestCaseOptions.defaults()).runAndGetResponse();
  }

  private void deleteObjectRestCall(String objectUri) throws Exception {
    new TestCase(mHostname, mPort, mBaseUri,
        objectUri, NO_PARAMS, HttpMethod.DELETE,
        TestCaseOptions.defaults()).runAndCheckResult();
  }

  private void deleteTagsRestCall(String uri) throws Exception {
    new TestCase(mHostname, mPort, mBaseUri,
        uri, ImmutableMap.of("tagging", ""), HttpMethod.DELETE,
        TestCaseOptions.defaults()).runAndCheckResult();
  }

  private TaggingData getTagsRestCall(String uri) throws Exception {
    String res = new TestCase(mHostname, mPort, mBaseUri,
        uri, ImmutableMap.of("tagging", ""), HttpMethod.GET,
        TestCaseOptions.defaults().setContentType(TestCaseOptions.XML_CONTENT_TYPE)
    ).runAndGetResponse();
    XmlMapper mapper = new XmlMapper();
    return mapper.readValue(res, TaggingData.class);
  }

  private void putTagsRestCall(String uri, @NotNull TaggingData tags) throws Exception {
    new TestCase(mHostname, mPort, mBaseUri,
        uri, ImmutableMap.of("tagging", ""), HttpMethod.PUT,
        TestCaseOptions.defaults()
            .setContentType(TestCaseOptions.OCTET_STREAM_CONTENT_TYPE)
            .setCharset(S3Constants.TAGGING_CHARSET)
            .setBody(TaggingData.serialize(tags).toByteArray()))
        .runAndCheckResult();
  }
}
