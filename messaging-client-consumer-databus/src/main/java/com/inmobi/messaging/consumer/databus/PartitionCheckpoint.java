package com.inmobi.messaging.consumer.databus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Writable;

public class PartitionCheckpoint implements Writable {
  private PartitionId id;
  private String fileName;
  private long offset;

  PartitionCheckpoint(String cluster, String collector, String fileName,
      long offset) {
    this(new PartitionId(cluster, collector), fileName, offset);
  }

  PartitionCheckpoint(PartitionId id, String fileName, long offset) {
    this.id = id;
    this.fileName = fileName;
    this.offset = offset;
  }

  PartitionCheckpoint(DataInput in) throws IOException {
    readFields(in);
  }

  public PartitionId getId() {
    return id;
  }

  public String getFileName() {
    return fileName;
  }

  public long getOffset() {
    return offset;
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    id = new PartitionId(in);
    fileName = in.readUTF();
    offset = in.readLong();
  }

  @Override
  public void write(DataOutput out) throws IOException {
    id.write(out);
    out.writeUTF(fileName);
    out.writeLong(offset);
  }

  public String toString() {
    // TODO:
    return "";
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((fileName == null) ? 0 : fileName.hashCode());
    result = prime * result + ((id == null) ? 0 : id.hashCode());
    result = prime * result + (int) (offset ^ (offset >>> 32));
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    PartitionCheckpoint other = (PartitionCheckpoint) obj;
    if (fileName == null) {
      if (other.fileName != null)
        return false;
    } else if (!fileName.equals(other.fileName))
      return false;
    if (id == null) {
      if (other.id != null)
        return false;
    } else if (!id.equals(other.id))
      return false;
    if (offset != other.offset)
      return false;
    return true;
  }

}
