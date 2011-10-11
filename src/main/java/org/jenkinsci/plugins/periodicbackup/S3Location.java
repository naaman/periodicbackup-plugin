package org.jenkinsci.plugins.periodicbackup;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import hudson.Extension;
import hudson.model.Hudson;
import hudson.util.FormValidation;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Stores and retrieves backups in Amazon Simple Storage Service (S3). Amazon credentials
 * are stored in accessKey and accessSecret fields in config.xml. In addition, a bucket
 * name is provided. A bucket will not be created for the backup operation -- the user
 * must provide the name of an existing bucket, otherwise the backup will fail.
 */
public class S3Location extends Location {
    private final String bucket;
    private final String accessKey;
    private final String accessSecret;
    private final Logger logger = Logger.getLogger(S3Location.class.getName());
    // S3 Metadata tag that specifies the timestamp of the backup object
    private static final String S3META_BACKUPTIMESTAMP = "backuptimestamp";
    // S3 Metadata flag where true means it is an archive that should be used as an archive
    private static final String S3META_ISJENKINSBACKUPARCHIVE = "jenkinsbackuparchive";
    // S3 Metadata flag where true means the file represents a BackupObject xml file
    private static final String S3META_ISJENKINSBACKUPOBJECT = "jenkinsbackupobject";

    @DataBoundConstructor
    public S3Location(String bucket, String accessSecret, String accessKey, boolean enabled) {
        super(enabled);
        this.bucket = bucket;
        this.accessSecret = accessSecret;
        this.accessKey = accessKey;
    }

    @Override
    public Iterable<BackupObject> getAvailableBackups() {
        AWSCredentials auth = new AWSCredentials(accessKey, accessSecret);
        S3Service s3Service;
        try {
            s3Service = new RestS3Service(auth);

            S3Object[] s3Objects = s3Service.listObjects(bucket);
            List<BackupObject> backups = new ArrayList<BackupObject>();
            for (S3Object s : s3Objects) {
                // make a second call to get the object because listObjects doesn't include
                // custom metadata. it doesn't appear there's a nice way to do this in bulk.
                S3Object objectWithMetadata = s3Service.getObject(bucket, s.getKey());
                if (objectWithMetadata.containsMetadata(S3META_ISJENKINSBACKUPOBJECT)
                        && objectWithMetadata.getMetadata(S3META_ISJENKINSBACKUPOBJECT).toString().equalsIgnoreCase("true")) {
                    StringWriter backupObjectAsXml = new StringWriter();
                    IOUtils.copy(objectWithMetadata.getDataInputStream(), backupObjectAsXml, Charsets.UTF_8.name());
                    backups.add((BackupObject) Hudson.XSTREAM.fromXML(backupObjectAsXml.toString()));
                }
                objectWithMetadata.closeDataInputStream();
            }
            Collections.sort(backups);
            return backups;
        } catch (S3ServiceException e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
            return Sets.newHashSet();
        } catch (IOException e) {
            logger.severe("An unhandled exception occurred while reading a backup. " + e.getMessage());
            return Sets.newHashSet();
        } catch (ServiceException e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
            return Sets.newHashSet();
        }
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {
        AWSCredentials auth = new AWSCredentials(accessKey, accessSecret);

        // convert the file into an actual BackupObject and get the timestamp it intends to use.
        Date backupObjectTimestamp = ((BackupObject) Hudson.XSTREAM.fromXML(new FileInputStream(backupObjectFile))).getTimestamp();
        String backupTimestamp = Util.getFormattedDate(BackupObject.FILE_TIMESTAMP_PATTERN, backupObjectTimestamp);
        logger.info("Creating S3 backup using " + backupTimestamp);
        
        S3Service s3Service;
        try {
            s3Service = new RestS3Service(auth);
            S3Bucket s3Bucket = s3Service.getBucket(bucket);

            logger.info("Creating S3 " + backupTimestamp + ".pbobj");

            // mark the BackupObject as a jenkinsbackupobject=true and jenkinsbackuparchive=false
            // so that the file can be identified as a BackupObject later.
            S3Object backupObject = new S3Object(backupObjectFile);
            backupObject.addMetadata(S3META_BACKUPTIMESTAMP, backupTimestamp);
            backupObject.addMetadata(S3META_ISJENKINSBACKUPARCHIVE, "false");
            backupObject.addMetadata(S3META_ISJENKINSBACKUPOBJECT, "true");
            s3Service.putObject(s3Bucket, backupObject);

            logger.info("Creating archive files in S3/" + bucket + "...");

            for (File file : archives) {
                // mark the archive file as jenkinsbackuparchive=true and jenkinsbackupobject=false
                // so that the file can be identified as an archive later.
                S3Object obj = new S3Object(file);
                obj.addMetadata(S3META_BACKUPTIMESTAMP, backupTimestamp);
                obj.addMetadata(S3META_ISJENKINSBACKUPARCHIVE, "true");
                obj.addMetadata(S3META_ISJENKINSBACKUPOBJECT, "false");
                s3Service.putObject(s3Bucket, obj);
            }

            logger.info("Archive files created in S3/" + bucket);
        } catch (Exception e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
            return;
        }
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {
        AWSCredentials auth = new AWSCredentials(accessKey, accessSecret);
        S3Service s3Service;
        try {
            logger.info("Logging into aws");
            s3Service = new RestS3Service(auth);

            S3Object[] s3Objects = s3Service.listObjects(bucket);
            List<File> backupsToRestore = new ArrayList<File>();

            for (S3Object s : s3Objects) {
                // make a second call to get the object because listObjects doesn't include
                // custom metadata. it doesn't appear there's a nice way to do this in bulk.
                S3Object objectWithMetadata = s3Service.getObject(bucket, s.getKey());
                logger.info(objectWithMetadata.toString());

                // if the object is a backup and the timestamp is equal, then add it to the list of backups to restore
                if (objectWithMetadata.containsMetadata(S3META_ISJENKINSBACKUPARCHIVE)
                    && objectWithMetadata.getMetadata(S3META_ISJENKINSBACKUPARCHIVE).equals("true")
                    && objectWithMetadata.containsMetadata(S3META_BACKUPTIMESTAMP)
                    && objectWithMetadata.getMetadata(S3META_BACKUPTIMESTAMP).toString().equals(
                        Util.getFormattedDate(BackupObject.FILE_TIMESTAMP_PATTERN, backup.getTimestamp()))) {
                    logger.info("Found backup: " + objectWithMetadata.toString());

                    String backupFileName = objectWithMetadata.getMetadata(S3META_BACKUPTIMESTAMP).toString()
                            + "." + backup.getStorage().getDescriptor().getArchiveFileExtension();
                    File backupFile = new File(tempDir, backupFileName);

                    // synchronously pull down the file and write it to the provided temp directory
                    // TODO: create an async option to download the restore in the background
                    FileUtils.writeByteArrayToFile(backupFile,
                            IOUtils.toByteArray(objectWithMetadata.getDataInputStream()));
                    backupsToRestore.add(backupFile);
                }
            }
            Collections.sort(backupsToRestore);
            return backupsToRestore;
        } catch (S3ServiceException e) {
            throw new PeriodicBackupException("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
        } catch (ServiceException e) {
            throw new PeriodicBackupException("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
        }
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        AWSCredentials auth = new AWSCredentials(accessKey, accessSecret);
        S3Service s3Service;
        try {
            logger.info("Logging into aws");
            s3Service = new RestS3Service(auth);

            S3Object[] s3Objects = s3Service.listObjects(bucket);

            for (S3Object s : s3Objects) {
                if (s.getName().startsWith(Util.generateFileNameBase(backupObject.getTimestamp()))) {
                    s3Service.deleteObject(bucket, s.getKey());
                }
            }
        } catch (S3ServiceException e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
        } catch (ServiceException e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
        }
    }

    public String getDisplayName() {
        return "S3 Bucket: " + bucket;  //To change body of implemented methods use File | Settings | File Templates.
    }

    public String getBucket() {
        return bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getAccessSecret() {
        return accessSecret;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(bucket, accessKey, accessSecret);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof S3Location) {
            S3Location that = (S3Location)o;
            return Objects.equal(this.bucket, that.bucket)
                && Objects.equal(this.accessKey, that.accessKey)
                && Objects.equal(this.accessSecret, that.accessSecret);
        }
        return false;
    }

    @Extension
    public static class S3LocationDescriptor extends LocationDescriptor {

        @Override
        public String getDisplayName() {
            return "S3";
        }

        public FormValidation doTestBucket(@QueryParameter String bucket,
                                           @QueryParameter String accessKey,
                                           @QueryParameter String accessSecret) {
            try {
                AWSCredentials unpw = new AWSCredentials(accessKey, accessSecret);
                S3Service s3 = new RestS3Service(unpw);

                if (s3.getBucket(bucket) == null)
                    return FormValidation.error("Invalid bucket name.");

                return FormValidation.ok("The connection is valid.");
            } catch (S3ServiceException e) {
                return FormValidation.error(e, "Unable to connect.");
            }
        }
    }
}
