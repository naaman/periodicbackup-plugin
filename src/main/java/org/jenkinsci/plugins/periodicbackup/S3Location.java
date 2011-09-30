package org.jenkinsci.plugins.periodicbackup;

import hudson.Extension;
import hudson.util.FormValidation;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: nnewbold
 * Date: 9/29/11
 * Time: 10:51 PM
 * To change this template use File | Settings | File Templates.
 */
public class S3Location extends Location {
    private final String bucket;
    private final String awsKey;
    private final String awsSecret;

    @DataBoundConstructor
    public S3Location(String bucket, String awsSecret, String awsKey, boolean enabled) {
        super(enabled);
        this.bucket = bucket;
        this.awsSecret = awsSecret;
        this.awsKey = awsKey;
    }

    @Override
    public Iterable<BackupObject> getAvailableBackups() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        
    }

    public String getDisplayName() {
        return "S3 Bucket";  //To change body of implemented methods use File | Settings | File Templates.
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

                return FormValidation.ok();
            } catch (S3ServiceException e) {
                return FormValidation.error(e, "Unable to connect.");
            }
        }
    }
}
