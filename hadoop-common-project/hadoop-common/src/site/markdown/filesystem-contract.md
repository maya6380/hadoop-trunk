<!---
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
-->

# Apache Hadoop FileSystem contract


## Introduction

This is a list of expected behaviors of a Hadoop-compatible filesystem,
including many that aren't (or can't be) explicitly tested -yet.

Most of the Hadoop operations are tested against HDFS in the Hadoop test
suites, because `MiniDFSCluster` is used so ubiquitously. HDFS's actions have
been modeled closely on the Posix Filesystem behavior -using the actions and
return codes of Unix filesystem actions as a reference.

What is not so rigorously tested is how well other filesystems accessible from
Hadoop behave. the bundled S3 filesystem makes Amazon's S3 blobstore accessible
through the FileSystem API. The Swift filesystem driver provides similar
functionality for the OpenStack Swift blobstore. The Azure object storage
filesystem in branch-1-win talks to Microsoft's Azure equivalent. All of these
bind to blobstores, which do have different behaviours, especially regarding
consistency guarantees, and atomicity of operations.

The Local filesystem provides access to the underlying filesystem of the
platform -its behaviour is defined by the operating system -and again, can
behave differently from HDFS.

Finally, there are filesystems implemented by third parties, that assert
compatibility with Apache Hadoop. There is no formal compatibility suite, and
hence no way for anyone to declare compatibility except in the form of their
own compatibility tests.

This document does not attempt to formally define compatibility; passing the
associated test suites does not guarantee correct behavior in MapReduce jobs,
or HBase operations.

What the test suites do define is the expected set of actions -failing these
tests will highlight potential issues.



### Naming

This document follows RFC2119 rules regarding the use of MUST, MUST NOT, MAY,
and SHALL -and MUST NOT be treated as normative.

The term "iff" is shorthand for "if and only if".


## Assumptions contained in the FileSystem uses

The original `FileSystem` class and its usages are based on a set of
assumptions "so obvious that nobody wrote them down" -primarily that HDFS is
the underlying filesystem, and that it offers a subset of the behavior of a
Posix filesystem

* It's a hierarchical directory structure with files and directories.

* Files contain data -possibly 0 bytes worth.

* You cannot put files or directories under a file

* Directories contain 0 or more files

* A directory entry has no data itself

* You can write arbitrary binary data to a file -and when that file's contents
 are read in, from anywhere in or out the cluster -that data is returned.

* You can store many GB of data in a single file.

* The root directory, `"/"`, always exists, and cannot be renamed. It is always a
  directory, and cannot be overwritten by a file write operation. An attempt to
  recursively delete the root directory will delete it's contents (assuming
  permissions allow this), but will retain the root path itself.

* You cannot rename/move a directory under itself.

* You cannot rename/move a directory atop any existing file other than the
  source file itself.

* Security: If you don't have the permissions for an operation, it will fail
  with some kind of error.

### Path Names

  * A path is comprised of path components separated by '/'.
  
  * Paths are compared based on unicode code-points. 
  
  * Case-insensitive and locale-specific comparisons MUST NOT not be used.
  
  * A path component is unicode string of 1 or more characters.
  
  * Path components MUST NOT include the characters `":"` or `"/"`.
  
  * Path components MUST NOT include characters of ASCII/UTF-8 value 0-31 .
  
  * Path components MUST NOT be `"."`  or `".."` 
  
  * Azure blob store says that paths SHOULD NOT use a trailing "." (as their
   .NET URI class strips it).

### Security Assumptions

Except in the special section on security, this document assumes the client has
full access to the filesystem. Accordingly, the majority of items in the list
do not add the qualification *assuming the user has the rights to perform the
operation with the supplied pa rameters and paths"*

The failure modes when a user lacks security permissions are not specified.

### Networking Assumptions

This document assumes this all network operations succeed -all statements
can be assumed to be qualified as *"assuming the operation does not fail to
to an undefined availability problem"*

* The final state of a filesystem after a network failure is undefined.

* The immediate consistency state of a filesystem after a network failure is undefined.

* If a network failure can be reported to the client, the failure MUST be an
  instance of `IOException`

* The exception details SHOULD include diagnostics suitable for an experienced
  Java developer _or_ operations team to begin diagnostics. For example: source
  and destination hostnames and ports on a `ConnectionRefused` exception.

* The exception details MAY include diagnostics suitable for inexperienced
  developers to begin diagnostics. For example Hadoop tries to include a
  reference to http://wiki.apache.org/hadoop/ConnectionRefused when a TCP
  connection request is refused.

## Core Requirements of a FileSystem


### Atomicity

* Rename of a file MUST be atomic.

* Rename of a directory SHOULD be atomic. Blobstore filesystems MAY offer
non-atomic directory renaming.

* Delete of a file MUST be atomic.

* Delete of an empty directory MUST be atomic.

* Recursive directory deletion MAY be atomic. Although HDFS offers atomic
recursive directory deletion, none of the other FileSystems that Hadoop supports
offers such a guarantee -including the local filesystems.

* `mkdir()` SHOULD be atomic.

* `mkdirs()` MAY be atomic. [It is *currently* atomic on HDFS, but this is not
the case for most other filesystems -and cannot be guaranteed for future
versions of HDFS]

* If `append()` is implemented, each individual `append()` operation SHOULD be atomic.

* `FileSystem.listStatus()` does not appear to contain any claims of atomicity,
  though some uses in the MapReduce codebase (such as `FileOutputCommitter`) do
  assume that the listed directories do not get deleted between listing their
  status and recursive actions on the listed entries.

### Consistency

The consistency model of a Hadoop filesystem is *one-copy-update-semantics*;
that generally that of a traditional Posix filesystem. 

* Create: once the `close()` operation on an output stream writing a newly
created file has completed, in-cluster operations querying the file metadata
and contents MUST immediately see the file and its data.

*  Update: Once  the `close()`  operation on  an output  stream writing  a newly
created file  has completed,  in-cluster operations  querying the  file metadata
and contents MUST immediately see the new data.

*  Delete:   once  a  `delete()`   operation  is   on  a  file   has  completed,
`listStatus()`, `open()`,`rename()` and `append()` operations MUST fail.

* When file is deleted then overwritten, `listStatus()`, `open()`,`rename()`
and `append()` operations MUST succeed: the file is visible.

* Rename:  after a rename  has completed, operations  against the new  path MUST
succeed; operations against the old path MUST fail.

* The consistency semantics out of cluster  MUST be the same as that in-cluster:
All clients  calling `read()` on  a closed file MUST  see the same  metadata and
data  until  it  is  changed  from  a  `create()`,  `append()`,  `rename()`  and
`append()` operation.

### Concurrency

* The data added to a file during a write or append MAY be visible while the
  write operation is in progress.

* If a client opens a file for a read()` operation while another `read()`
  operation is in progress, the second operation MUST succeed. Both clients
  MUST have a consistent view of the same data.

* If a file is deleted while a `read()` operation is in progress, the
  `delete()` operation SHOULD complete successfully. Implementations MAY cause
  `delete()` to fail with an `IOException` instead.
 
* If a file is deleted while a `read()` operation is in progress, the `read()`
  operation MAY complete successfully. Implementations MAY cause `read()`
  operations to fail with an `IOException` instead.

* Multiple writers MAY open a file for writing. If this occurs, the outcome
is undefined

* Undefined: action of `delete()` while a write or append operation is in
  progress

### Undefined limits

Here are some limits to filesystem capacity that have never been explicitly
defined.

 1. The maximum # of files in a directory.

 1. Max # of directories in a directory

 1. Maximum total number of entries (files and directories) in a filesystem.

 1. Max length of a filename (HDFS: 8000)

 1. `MAX_PATH` - the total length of the entire directory tree referencing a
  file. Blobstores tend to stop at ~1024 characters

 1. max depth of a path (HDFS: 1000 directories)

 1. The maximum size of a single file

### Undefined timeouts

Timeouts for operations are not defined at all, including:

* The maximum completion time of blocking FS operations.
MAPREDUCE-972 shows how distcp broke on slow s3 renames.

* The timeout for idle read streams before they are closed.

* The timeout for idle write streams before they are closed.

The blocking-operation timeout is in fact variable in HDFS, as sites and
clients may tune the retry parameters so as to convert filesystem failures and
failovers into pauses in operation. Instead there is a general assumption that
FS operations are "fast but not as fast as local FS operations", and that data
reads and writes take a time that scales with the volume of data. This
assumption shows a more fundamental one: the filesystem is "close" to the
client applications as far as network latency and bandwidth is concerned.

There are also some implicit assumptions about the overhead of some operations

1. `seek()` operations are fast and incur little or no network delays. [This 
does not hold on blob stores]

1. Directory list operations are fast for directories with few entries.

1. Directory list operations are fast for directories with few entries, but may
incur a cost that is O(no. of entries). Hadoop 2.x adds iterative listing to
handle the challenge of listing directories with millions of entries without
buffering.

1. A `close()` of an `OutputStream` is fast, irrespective of whether or not
the file operation has succeeded or not.

## Operation-specific requirements

This section attempts to define the expected behaviors of specific operations
of the FileSystem implementations

### `read()` operations

* An attempt to open a nonexistent path for reading MUST raise a `FileNotFoundException`.
* read() operations at the end of the file MUST return -1

* `readFully()` calls that attempt to read past the end of a file SHOULD raise
  an `EOFException`. Implementations MAY raise a different class of
  `IOException` or subclass thereof.

#### `Seekable.seek(position)`

* A `seek(position)` followed by a read operation MUST position the cursor
such that the next read will return the data starting at `byte[position]`

* If the position is past the end of the file, the stream MUST throw
an `IOException`, which SHOULD be an `EOFException`.

* If the file is closed, then an `IOException` MUST be raised.

* After a `seek()` operation, the result of `getPos()` is the location that was
just seek()'d to.

#### `Seekable.seekToNewSource(position)`

* This SHOULD return false if the filesystem does not implement multiple-data
  sources for files.

* Irrespective of whether or a `seekToNewSource()` operation succeeds or fails,
the stream's `getPos()` value MUST be the value which it was before the seek
operation was invoked. Specifically, it is not a `seek()` operation, it is a
request to bind to a new location of data in expectation of a read or seek
operation fetching the new data.

### `rename(src,dest)`

* The parent directories of a the destination file/directory MUST exist for
the rename to succeed.

* `rename(self, self)` MUST return true if `isFile(self)` but MUST be a no-op:
the file and its attributes are not updated.

* `rename(self, self)` MUST be a no-op `isDirectory(self)`. The return value
  MAY be true. The return value MAY be false. HDFS returns true. *This is a
  departure from the behavior of Posix, which requires the operation to fail
  with a non-zero status code.*

* `rename(path, path2)` MUST fail if `!exists(path)`

* `rename(path, path2)` MUST fail if `is-subdirectory-of(path, path2)` is true.

* `rename(path, path2)` MUST fail if `!exists(path2)` and `isFile(path2)`.

* `rename("/",anything)` MUST always fail. This implicitly covered
by the child directory rename rule.

_Notes_

* The behavior of `rename()` on an open file is unspecified.

* The return code of renaming a directory to itself is unspecified. 

### `delete(Path path, boolean recursive)`


* Deleting an empty directory MUST delete the directory and return `true`,
irrespective of the value of the recursive flag.

* Deleting a directory with child elements MUST succeed iff `recursive==true`.

* Deleting the root path, `/`, MUST, iff `recursive==true`, delete all entries
in the filesystem, excluding the `/` entry itself. That is, `exists("/")`
must still be true

* If the filesystem is empty, deleting the root path, `/`, MUST succeed and
  MUST have no effect.
 
* Deleting a non-empty root directory, `/`, MUST return false if `recursive==false`.

* Deleting a file is an atomic action.

* Deleting an empty directory is atomic.

* A recursive delete of a directory tree MAY be atomic.

* After a delete operation completes successfully, attempts by clients to open
  the file, query its attributes, or locate it by enumerating the parent
  directory will fail. (i.e changes are immediately visible and consistent
  across all clients)

_Notes_

Posix permits deletion of files that are open. This does not hold in Windows; a
fact that has broken some tests. It's not clear whether real-world Hadoop
applications depend upon this property.

### `FileSystem.listStatus()` 


* `FileSystem.listStatus("/", filter)` MUST return the filtered child
directory entries

* `FileSystem.listStatus(existing-directory, filter)` succeeds and returns
0 or more children in the directory. Entries that match the filter are excluded.

* `FileSystem.listStatus(existing-file, filter)` succeeds and returns the
status of the file as the single element of the array, an element where
`FileStatus.isDirectory()` returns false.
_Exception_: if the file matches any filter, an empty array MUST be returned.

* `FileSystem.listStatus(non-existing-file, filter)` MUST throw a
 `FileNotFoundException`

* After a file is created, all `listStatus()` operations on the file and parent
  directory MUST find the file.

* After a file is deleted, all `listStatus()` operations on the file and parent
  directory MUST NOT find the file.

* By the time the `listStatus()` operation returns to the caller, there
is no guarantee that the information contained in the response is current.
The details MAY be out of date -including the contents of any directory, the
attributes of any files, and the existence of the path supplied. 

* Security: if a caller has the rights to call `listStatus()` a directory or
file, it has the rights to `listStatus()` all parent directories. 

### `getFileBlockLocations(Path path, int start, int len)`

The following conditions must be met in order:
 
 1. The operation MUST return null if `(path==null)`
 
 1. MUST throw an `InvalidArgumentException` if `(src<0 || len<0)`
 
 1. MUST throw an `IOException` instance if `path.isDirectory()` is true
 
 1. If the filesystem is not location-aware it SHOULD return
`"localhost","localhost:????"` as the response.

### `create(Path file, FsPermission permission, boolean overwrite, int bufferSize, short replication, long blockSize, Progressable progress)`


* `create()` MUST create parent directories according to the `mkdirs()` rules
if the path's parent directories are missing.

* `create()` MUST fail if the Path `file` resolves to an existing file or
directory AND `overwrite==false`

### `OutputStream.flush()` for output streams writing to a `FileSystem`


* Client-side `flush()` SHOULD forward the data to the DFS.

* The DFS MAY flush that data to disk.

### `OutputStream.close()` for output streams writing to a `FileSystem`


Durability: Once a `close()` operation has successfully completed, the data
MUST be persisted according to the durability guarantees of the filesystem.

### Block Size data

Block-based filesystems have a block size, which is used by higher layers
in the Hadoop stack -specifically the MapReduce layer-. For that reason,
even if a filesystem does not have a notion of blocks, it MUST provide
a blocksize to callers -ideally one large enough to ensure that Map tasks
are given a large amount of data to work through. 


* FileSystem implementations MUST provide a block size of size >1.

* FileSystem implementations SHOULD provide a block size large enough
for the layers above to perform useful work. 

* Block-based filesystems SHOULD provide the actual size of blocks of files.

* FileSystem implementations MUST return a block size >=1 in response to calls
of `getDefaultBlockSize()` and `getDefaultBlockSize(Path)`
block size of size >1.

* FileSystem implementations MUST return a block size >=1 in response to calls
of `getBlockSize(path)` when `path` references a file. 

* `getBlockSize(path)` SHOULD throw a `FileNotFoundException` if the 
path does not exist. (ISSUE: SHOULD vs MUST vs MAY?)

* For all `FileStatus fs = getFileStatus(path)` where `path` exists and is a file,
`fs.getBlockSize()` must return a block size >=1

* The value of `FileSystem.getBlockSize(path)` MUST match the value of
`getFileStatus(path).getBlockSize()`


## Network Failures


* Any operation with a filesystem MAY signal an error by throwing an
  `IOException` or subclass thereof.

* The specific cause of the failure SHOULD be explicitly defined in the
  `IOException` subclass type. This is aid diagnostics.

* The cause or details of the failure SHOULD be described in the exception's
`toString()` value. This to aid diagnostics.

* Filesystem operations MUST NOT throw `RuntimeException` exceptions on the
failure of a remote operations, authentication or other operational problems.

* Stream read operations MAY fail if the read channel has been idle for a
Filesystem-specific period of time.

* Stream write operations MAY fail if the write channel has been idle for
a Filesystem-specific period of time.

* Network failures MAY be raised in the Stream `close()` operation

