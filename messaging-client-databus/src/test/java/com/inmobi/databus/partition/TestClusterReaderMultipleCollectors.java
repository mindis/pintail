package com.inmobi.databus.partition;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.inmobi.databus.Cluster;
import com.inmobi.databus.readers.CollectorStreamReader;
import com.inmobi.databus.readers.DatabusStreamWaitingReader;
import com.inmobi.messaging.consumer.databus.DataEncodingType;
import com.inmobi.messaging.consumer.databus.QueueEntry;
import com.inmobi.messaging.consumer.util.TestUtil;

public class TestClusterReaderMultipleCollectors {

  private static final String testStream = "testclient";

  private String[] collectors = new String[] {"collector1", "collector2"};
  private static final String clusterName = "testCluster";
  private PartitionId partitionId = new PartitionId(clusterName, null);
  private LinkedBlockingQueue<QueueEntry> buffer = 
      new LinkedBlockingQueue<QueueEntry>(150);
  private PartitionReader preader;
  private Cluster cluster;
  private boolean isLocal = false;
  private String[] files = new String[] {TestUtil.files[1], TestUtil.files[3],
      TestUtil.files[5], TestUtil.files[6]};
  Path[] databusFiles1 = new Path[3];
  Path[] databusFiles2 = new Path[3];
  FileSystem fs;

  @BeforeTest
  public void setup() throws Exception {
    // initialize config
    cluster = TestUtil.setupLocalCluster(this.getClass().getSimpleName(),
        testStream, new PartitionId(clusterName, collectors[0]), files, null,
        databusFiles1, 0, 1);
    TestUtil.setUpFiles(cluster, collectors[1], files, null, databusFiles2, 0,
        1);
    fs = FileSystem.get(cluster.getHadoopConf());
  }

  @AfterTest
  public void cleanup() throws IOException {
    TestUtil.cleanupCluster(cluster);
  }

  @Test
  public void testReadFromStart() throws Exception {
    preader = new PartitionReader(partitionId, null, cluster, buffer,
        testStream, CollectorStreamReader.getDateFromCollectorFile(files[0]),
        1000, 1000, isLocal, DataEncodingType.BASE64, false);
    preader.init();
    Assert.assertTrue(buffer.isEmpty());
    Assert.assertEquals(preader.getReader().getClass().getName(),
        ClusterReader.class.getName());
    Assert.assertEquals(((ClusterReader)preader.getReader())
        .getReader().getClass().getName(),
        DatabusStreamWaitingReader.class.getName());
    preader.start();
    while (buffer.remainingCapacity() > 0) {
      Thread.sleep(10);
    }

    TestUtil.incrementCommitTime();
    Path movedPath1 = TestUtil.moveFileToStreams(fs, testStream, collectors[1],
        cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[1]),
        files[1]);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(databusFiles1[0])), 1, 0, 100, partitionId,
        buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(databusFiles2[0])), 1, 0, 50, partitionId,
        buffer);

    while (buffer.remainingCapacity() > 0) {
      Thread.sleep(10);
    }
    TestUtil.incrementCommitTime();
    Path movedPath2 = TestUtil.moveFileToStreams(fs, testStream, collectors[1],
        cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[1]),
        files[2]);
    Path movedPath3 = TestUtil.moveFileToStreams(fs, testStream, collectors[0],
        cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[0]),
        files[1]);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(databusFiles2[0])), 1, 50, 50, partitionId,
        buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath1)), 2, 0, 100, partitionId,
        buffer);

    while (buffer.remainingCapacity() > 0) {
      Thread.sleep(10);
    }
    TestUtil.incrementCommitTime();
    Path movedPath4 = TestUtil.moveFileToStreams(fs, testStream, collectors[0],
        cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[0]),
        files[2]);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath3)), 2, 0, 100, partitionId,
        buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath2)), 3, 0, 50, partitionId,
        buffer);
    while (buffer.remainingCapacity() > 0) {
      Thread.sleep(10);
    }
    TestUtil.incrementCommitTime();
    Path movedPath5 = TestUtil.moveFileToStreams(fs, testStream, collectors[1],
    cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[1]),
    files[3]);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath2)), 3, 50, 50, partitionId,
    buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath4)), 3, 0, 50, partitionId,
    buffer);
    while (buffer.remainingCapacity() > 0) {
      Thread.sleep(10);
    }
    Path movedPath6 = TestUtil.moveFileToStreams(fs, testStream, collectors[0],
    cluster, TestUtil.getCollectorDir(cluster, testStream, collectors[0]),
    files[3]);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath4)), 3, 50, 50, partitionId,
        buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath5)), 4, 0, 100, partitionId,
    buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath6)), 4, 0, 100, partitionId,
    buffer);
    Assert.assertTrue(buffer.isEmpty());
    preader.close();
    
    preader = new PartitionReader(partitionId, new PartitionCheckpoint(
        DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath5)), 50), cluster, buffer,
        testStream, null,
        1000, 1000, isLocal, DataEncodingType.BASE64, false);
    preader.start();
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath5)), 4, 50, 50, partitionId,
    buffer);
    TestUtil.assertBuffer(DatabusStreamWaitingReader.getHadoopStreamFile(
        fs.getFileStatus(movedPath6)), 4, 0, 100, partitionId,
    buffer);
    Assert.assertTrue(buffer.isEmpty());
    preader.close();

  }

}
