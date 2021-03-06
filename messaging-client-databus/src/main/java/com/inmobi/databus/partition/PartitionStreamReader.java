package com.inmobi.databus.partition;

/*
 * #%L
 * messaging-client-databus
 * %%
 * Copyright (C) 2012 - 2014 InMobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;

import com.inmobi.databus.files.StreamFile;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.consumer.databus.MessageCheckpoint;

public interface PartitionStreamReader {

  void initializeCurrentFile() throws IOException, InterruptedException;

  StreamFile getCurrentFile();

  long getCurrentLineNum();

  boolean openStream() throws IOException;

  Message readLine() throws IOException, InterruptedException;

  void closeStream() throws IOException;

  void close() throws IOException;

  MessageCheckpoint getMessageCheckpoint();

  MessageCheckpoint buildStartPartitionCheckpoints();
}
