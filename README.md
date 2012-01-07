# How to use

1. Create the mount location

        mkdir /mnt/hdfs

2. Add this entry to /etc/fstab

        localhost:/   /mnt/hdfs   nfs4       rw,intr,timeo=600,proto=tcp,port=2050      0 0

3. Ensure you have maven installed and hadoop command configured with *-site.xml pointing at the namenode.

4. Build the package with dependencies and start

        mvn package && ./start-nfs-server.sh

    Which will build, test, and then startup the HDFS NFS Proxy using CDH. You can use Apache Hadoop 0.23 or
    greater with the following command:

        mvn package -Dhadoop.version=0.23.0-SNAPSHOT


5. Mount hdfs

       sudo mount /mnt/hdfs

6. You should now be able to access HDFS. Note: The script ./start-nfs-client-tests.sh runs basic tests.

# What needs improvement (in no order)

* Locking:
NFS4Handler has coarse grained locking and is heavily used
* User Mapping: 
NFS4 User identities are user@domain. However, the RPC protocol uses UID/GID.
Currently we map the UID on the incoming request via the system the daemon executes on.
I think there is something in Hadoop which does user mapping as well. If so, it might
make sense to be consistent.
* RFC 3530 (NFS4):
    - Client ID logic is complex and not completely followed.
    - Many reccomended attributes are not implemented such as 14 archive, 25 hidden,
      49 timebackup, 55 mounted on fileid
    - Kerberos
    - File appends
    - Filehandles and not persisted and at present if the server restarts, all clients
      need to be restarted.
* Read Ordering: 
We recieve a fair number of threads blocked on reads of a single input stream.
I think we could get better performance if we ordered these like writes because we
know they will arrive out of order. As such the current impl is doing more seeks
than required. We also might considering pooling input streams.
* Garbage Collection:
Heavy read loads use a fair amount of old gen likely due to our response cache
and the size of read responses. If this becomes an issue we could easily exclude 
read requests from the response cache.
* Write Ordering:
We buffer writes until we find the prereq, this memory consumption is not bounded.
* Metrics:
A simple metrics system is used. We should use Hadoops Metric System. 
* Damn Ugly: 
    - DirectoryEntry.getWireSize is sick and wrong
    - Bitmap

# FAQ
* All user/groups show up as nobody?

NFS4 returns user/group with user@domain. Today by default it responds with
user@clientDomain. The client then uses the idmap service to lookup the user
for a uid. As such, it's likely you have not configured idmap.

Say the domain is acme.com, you would change: /etc/idmapd.conf from:

    Domain = localdomain

to:

    Domain = bashkew.com

and then restart idmapd:
 
    /etc/init.d/rpcidmapd restart

* I am trying to do an operation as any user who is not running the daemon and
I get errors?

Unless you have Secure Impersonation configured for the user running the proxy
(http://hadoop.apache.org/common/docs/current/Secure_Impersonation.html)
or are running the proxy as the same user who is running the namenode you will
only be able to access HDFS as user running the proxy.

Say I have the proxy running as noland and I copy a file as root into
/user/root, I will get this error below:

    org.apache.hadoop.security.AccessControlException: Permission denied: 
      user=noland, access=WRITE, inode="/user/root":root:hadoop:drwxr-xr-x

