package com.inmobi.databus.readers;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;

import com.inmobi.databus.files.FileMap;
import com.inmobi.databus.files.HadoopStreamFile;
import com.inmobi.databus.partition.PartitionCheckpoint;
import com.inmobi.databus.partition.PartitionCheckpointList;
import com.inmobi.databus.partition.PartitionId;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.metrics.PartitionReaderStatsExposer;

public class DatabusStreamWaitingReader 
    extends DatabusStreamReader<HadoopStreamFile> {

  private static final Log LOG = LogFactory.getLog(
      DatabusStreamWaitingReader.class);

  private int currentMin;
  private final Set<Integer> partitionMinList;
  private PartitionCheckpointList partitionCheckpointList;
  private boolean movedToNext;
  private int prevMin;
  private Map<Integer, Date> checkpointTimeStampMap;
  private Map<Integer, PartitionCheckpoint> pChkpoints;

  public DatabusStreamWaitingReader(PartitionId partitionId, FileSystem fs,
      Path streamDir,  String inputFormatClass, Configuration conf,
      long waitTimeForFileCreate, PartitionReaderStatsExposer metrics,
      boolean noNewFiles, Set<Integer> partitionMinList, 
      PartitionCheckpointList partitionCheckpointList, Date stopTime)
          throws IOException {
    super(partitionId, fs, streamDir, inputFormatClass, conf,
        waitTimeForFileCreate, metrics, noNewFiles, stopTime);
    this.partitionCheckpointList = partitionCheckpointList;
    this.partitionMinList = partitionMinList; 
    this.stopTime = stopTime;
    currentMin = -1;
    this.checkpointTimeStampMap = new HashMap<Integer, Date>();
    if (partitionCheckpointList != null) {
      pChkpoints = partitionCheckpointList.getCheckpoints();
      prepareTimeStampsOfCheckpoints();
    }
  }

  public void prepareTimeStampsOfCheckpoints() {
    PartitionCheckpoint partitionCheckpoint = null;
    for (Integer min : partitionMinList) {
      partitionCheckpoint = pChkpoints.get(min);
      if (partitionCheckpoint != null) {
        Date checkpointedTimestamp = getDateFromCheckpointPath(
            partitionCheckpoint.getFileName());
        checkpointTimeStampMap.put(min, checkpointedTimestamp);
      } else {
        checkpointTimeStampMap.put(min, null);
      }
    }
  }


  /**
   * This method is used to check whether the given minute directory is 
   * completely read or not. It takes the current time stamp and the minute 
   * on which the reader is currently working. It retrieves the partition checkpoint 
   * for that minute if it contains. It compares the current time stamp with 
   * the checkpointed time stamp. If current time stamp is before the 
   * checkpointed time stamp then that minute directory for the current hour is 
   * completely read. If both the time stamps are same then it checks line number.
   * If line num is -1 means all the files in that minute dir are already read.
   */ 
  public boolean isRead(Date currentTimeStamp, int minute) {
    Date checkpointedTimestamp = checkpointTimeStampMap.get(
        Integer.valueOf(minute));
    if (checkpointedTimestamp == null) {
      return false;
    }
    if (currentTimeStamp.before(checkpointedTimestamp)) {
      return true;
    } else if (currentTimeStamp.equals(checkpointedTimestamp)) {
      PartitionCheckpoint partitionCheckpoint = pChkpoints.get(
          Integer.valueOf(minute));
      if (partitionCheckpoint != null && partitionCheckpoint.getLineNum() == -1)
      {
        return true;
      }
    }
    return false;
  }

  /**
   * It reads from the next checkpoint. It retrieves the first file from the filemap. 
   * Get the minute id from the file and see the checkpoint value. If the 
   * checkpointed file is not same as current file then it sets the iterator to 
   * the checkpointed file if the checkpointed file exists. 
   */
  public boolean initFromNextCheckPoint() throws IOException {
    initCurrentFile();
    currentFile = getFirstFileInStream();
    Date date = DatabusStreamWaitingReader.getDateFromStreamDir(streamDir,
        currentFile.getPath().getParent());
    Calendar current = Calendar.getInstance();
    current.setTime(date);
    int currentMinute =current.get(Calendar.MINUTE);
    PartitionCheckpoint partitioncheckpoint = partitionCheckpointList.
        getCheckpoints().get(currentMinute);

    if (partitioncheckpoint != null) {
      Path checkpointedFileName =new Path(streamDir, 
          partitioncheckpoint.getFileName());
      if (!(currentFile.getPath()).equals(checkpointedFileName)) {
        if(fs.exists(checkpointedFileName)) {
          currentFile = fs.getFileStatus(checkpointedFileName);
          currentLineNum = partitioncheckpoint.getLineNum();
        } else {
          currentLineNum = 0;
        }
      } else {
        currentLineNum = partitioncheckpoint.getLineNum();
      }
    }
    if (currentFile != null) {
      LOG.debug("CurrentFile:" + getCurrentFile() + " currentLineNum:" +
          currentLineNum);
      setIterator();
    }
    return currentFile != null;
  }

  @Override
  protected void buildListing(FileMap<HadoopStreamFile> fmap,
      PathFilter pathFilter) throws IOException {
    Calendar current = Calendar.getInstance();
    Date now = current.getTime();
    current.setTime(buildTimestamp);
    boolean breakListing = false;
    while (current.getTime().before(now)) {
      Path hhDir =  getHourDirPath(streamDir, current.getTime());
      int hour = current.get(Calendar.HOUR_OF_DAY);
      if (fs.exists(hhDir)) {
        while (current.getTime().before(now) && 
            hour  == current.get(Calendar.HOUR_OF_DAY)) {
          // stop the file listing if stop date is beyond current time.
          if (checkAndSetstopTimeReached(current)) {
            breakListing = true;
            break;
          }
          int min = current.get(Calendar.MINUTE);
          Date currenTimestamp = current.getTime();
          // Move the current minute to next minute
          current.add(Calendar.MINUTE, 1);
          if (partitionMinList.contains(Integer.valueOf(min))
              && !isRead(currenTimestamp, min)) {
            Path dir = getMinuteDirPath(streamDir, currenTimestamp);
            if (fs.exists(dir)) {
              Path nextMinDir = getMinuteDirPath(streamDir, current.getTime());
              if (fs.exists(nextMinDir)) {
                doRecursiveListing(dir, pathFilter, fmap);
              } else {
                LOG.info("Reached end of file listing. Not looking at the last" +
                    " minute directory:" + dir);
                breakListing = true;
                break;
              }
            }
          }
        }
      } else {
        // go to next hour
        LOG.info("Hour directory " + hhDir + " does not exist");
        current.add(Calendar.HOUR_OF_DAY, 1);
        current.set(Calendar.MINUTE, 0);
      }
      if (breakListing) {
        break;
      }
    }

    if (getFirstFileInStream() != null && (currentMin == -1)) {
      Date currentDate = DatabusStreamWaitingReader.getDateFromStreamDir(
          streamDir, getFirstFileInStream().getPath().getParent());
      currentMin = getMinuteFromDate(currentDate);
    }
  }

  /*
   * check whether reached stopTime and stop the File listing if it reached stopTime
   */
  private boolean checkAndSetstopTimeReached(Calendar current) {
    if (stopTime != null && stopTime.before(current.getTime())) {
      LOG.info("Reached stopTime. Not listing from after" +
          " the stop date ");
      stopListing();
      return true;
    }
    return false;
  }

  /**
   * This method does the required setup before moving to next file. First it 
   * checks whether the both current file and next file belongs to same minute 
   * or different minutes. If files exists on across minutes then it has to 
   * check the next file is same as checkpointed file. If not same and checkpointed 
   * file exists then sets the iterator to the checkpointed file. 
   * @return false if it reads from the checkpointed file.
   */
  @Override
  public boolean prepareMoveToNext(FileStatus currentFile, FileStatus nextFile) 
      throws IOException {                              
    Date date = getDateFromStreamDir(streamDir, currentFile.getPath().
        getParent());
    Calendar now = Calendar.getInstance();
    now.setTime(date);
    currentMin = now.get(Calendar.MINUTE);

    date = getDateFromStreamDir(streamDir, nextFile.getPath().getParent());
    now.setTime(date);

    boolean readFromCheckpoint = false;
    FileStatus fileToRead = nextFile;
    if (currentMin != now.get(Calendar.MINUTE)) {
      //We are moving to next file, set the flags so that Message checkpoints
      //can be populated.
      movedToNext = true;
      prevMin = currentMin;
      currentMin = now.get(Calendar.MINUTE);
      PartitionCheckpoint partitionCheckpoint = partitionCheckpointList.
          getCheckpoints().get(currentMin);
      if (partitionCheckpoint != null && partitionCheckpoint.getLineNum() != -1)
      {
        Path checkPointedFileName = new Path(streamDir, 
            partitionCheckpoint.getFileName());
        //set iterator to checkpoointed file if there is a checkpoint
        if(!fileToRead.getPath().equals(checkPointedFileName)) {
          if (fs.exists(checkPointedFileName)) {
            fileToRead = fs.getFileStatus(checkPointedFileName);
            currentLineNum = partitionCheckpoint.getLineNum();
          } else {
            currentLineNum = 0;
          }
        } else {
          currentLineNum = partitionCheckpoint.getLineNum();
        }
        readFromCheckpoint = true;
      }
      updatePartitionCheckpointList(prevMin);
    }
    this.currentFile = fileToRead;
    setIterator();
    return !readFromCheckpoint;
  }

  private void updatePartitionCheckpointList(int prevMin) {
    Map<Integer, PartitionCheckpoint> pckList = partitionCheckpointList.
        getCheckpoints();
    pckList.remove(prevMin);
    partitionCheckpointList.setCheckpoint(pckList);
  }

  @Override
  protected HadoopStreamFile getStreamFile(Date timestamp) {
    return new HadoopStreamFile(getMinuteDirPath(streamDir, timestamp),
        null, null);
  }

  protected HadoopStreamFile getStreamFile(FileStatus status) {
    return getHadoopStreamFile(status);
  }

  protected void startFromNextHigher(FileStatus file)
      throws IOException, InterruptedException {
    if (!setNextHigherAndOpen(file)) {
      waitForNextFileCreation(file);
    }
  }

  private void waitForNextFileCreation(FileStatus file)
      throws IOException, InterruptedException {
    while (!closed && !setNextHigherAndOpen(file) && !hasReadFully()) {
      LOG.info("Waiting for next file creation");
      waitForFileCreate();
      build();
    }
  }

  @Override
  public Message readLine() throws IOException, InterruptedException {
    Message line = readNextLine();
    while (line == null) { // reached end of file
      LOG.info("Read " + getCurrentFile() + " with lines:" + currentLineNum);
      if (closed) {
        LOG.info("Stream closed");
        break;
      }
      if (!nextFile()) { // reached end of file list
        LOG.info("could not find next file. Rebuilding");
        build(getDateFromStreamDir(streamDir, 
            getCurrentFile()));
        if (!nextFile()) { // reached end of stream
          // stop reading if read till stopTime
          if (hasReadFully()) {
            LOG.info("read all files till stop date");
            break;
          }
          LOG.info("Could not find next file");
          startFromNextHigher(currentFile);
          LOG.info("Reading from next higher file "+ getCurrentFile());
        } else {
          LOG.info("Reading from " + getCurrentFile() + " after rebuild");
        }
      } else {
        // read line from next file
        LOG.info("Reading from next file " + getCurrentFile());
      }
      line = readNextLine();
    }
    return line;
  }

  @Override
  protected FileMap<HadoopStreamFile> createFileMap() throws IOException {
    return new FileMap<HadoopStreamFile>() {
      @Override
      protected void buildList() throws IOException {
        buildListing(this, pathFilter);
      }

      @Override
      protected TreeMap<HadoopStreamFile, FileStatus> createFilesMap() {
        return new TreeMap<HadoopStreamFile, FileStatus>();
      }

      @Override
      protected HadoopStreamFile getStreamFile(String fileName) {
        throw new RuntimeException("Not implemented");
      }

      @Override
      protected HadoopStreamFile getStreamFile(FileStatus file) {
        return HadoopStreamFile.create(file);
      }

      @Override
      protected PathFilter createPathFilter() {
        return new PathFilter() {
          @Override
          public boolean accept(Path path) {
            if (path.getName().startsWith("_")) {
              return false;
            }
            return true;
          }          
        };
      }
    };
  }

  public static Date getBuildTimestamp(Path streamDir,
      PartitionCheckpoint partitionCheckpoint) {
    try {
      return getDateFromCheckpointPath(partitionCheckpoint.getFileName());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid checkpoint:" + 
          partitionCheckpoint.getStreamFile(), e);
    }
  }

  public static HadoopStreamFile getHadoopStreamFile(FileStatus status) {
    return HadoopStreamFile.create(status);
  }

  public void resetMoveToNextFlags() {
    movedToNext = false;
    prevMin = -1;
  }

  public boolean isMovedToNext() {
    return movedToNext;
  }

  public int getPrevMin() {
    return this.prevMin;
  }

  public int getCurrentMin() {
    return this.currentMin;
  }

  /**
   * @returns Zero  if checkpoint is not present for that minute or
   *                checkpoint file and current file were not same.
   *          Line number from checkpoint
   */
  @Override
  protected long getLineNumberForCurrentFile(FileStatus currentFile) {
    Date currentTimeStamp = getDateFromStreamDir(streamDir, currentFile.
        getPath().getParent());
    int minute = getMinuteFromDate(currentTimeStamp);
    PartitionCheckpoint partitionChkPoint = pChkpoints.get(Integer.valueOf(minute));
    if (partitionChkPoint != null) {
      String currentFileName = currentFile.getPath().toString();
      String currentFileNameSubstring = currentFileName.substring(
          streamDir.toString().length() + 1);
      // check whether current file and checkpoint file are same
      if (currentFileNameSubstring.equals(partitionChkPoint.getFileName())) {
        return partitionChkPoint.getLineNum();
      }
    }
    return 0;
  }

  private int getMinuteFromDate(Date currentTimeStamp) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(currentTimeStamp);
    return cal.get(Calendar.MINUTE);
  }
}