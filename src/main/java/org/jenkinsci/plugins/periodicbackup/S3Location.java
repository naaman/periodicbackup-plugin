package org.jenkinsci.plugins.periodicbackup;

import com.google.common.base.Charsets;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import hudson.Extension;
import hudson.model.Hudson;
import hudson.util.FormValidation;
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

import javax.xml.stream.events.Characters;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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
    private static final String S3META_BACKUPTIMESTAMP = "backuptimestamp";
    private static final String S3META_JENKINSBACKUP = "jenkinsbackup";
    private static final String S3META_ISBACKUPOBJECT = "isbackupobject";

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
                if (objectWithMetadata.containsMetadata(S3META_ISBACKUPOBJECT)
                        && objectWithMetadata.getMetadata(S3META_ISBACKUPOBJECT).toString().equalsIgnoreCase("true")) {
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
        String backupTimestamp = DateFormat.getInstance().format(System.currentTimeMillis());
        S3Service s3Service;
        try {
            s3Service = new RestS3Service(auth);
            S3Bucket s3Bucket = s3Service.getBucket(bucket);
            S3Object backupObject = new S3Object(backupObjectFile);
            backupObject.addMetadata(S3META_BACKUPTIMESTAMP, backupTimestamp);
            backupObject.addMetadata(S3META_JENKINSBACKUP, "true");
            backupObject.addMetadata(S3META_ISBACKUPOBJECT, "true");
            s3Service.putObject(s3Bucket, backupObject);
            for (File file : archives) {
                S3Object obj = new S3Object(file);
                obj.addMetadata(S3META_BACKUPTIMESTAMP, backupTimestamp);
                obj.addMetadata(S3META_JENKINSBACKUP, "true");
                obj.addMetadata(S3META_ISBACKUPOBJECT, "false");
                s3Service.putObject(s3Bucket, obj);
            }
        } catch (Exception e) {
            logger.severe("An unhandled exception occurred while connecting to Amazon S3. " + e.getMessage());
            return;
        }
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {
        backup.getDisplayName();
        // TODO: implement this method
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        
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
