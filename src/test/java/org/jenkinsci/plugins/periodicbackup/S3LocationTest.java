package org.jenkinsci.plugins.periodicbackup;

import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: nnewbold
 * Date: 9/29/11
 * Time: 11:06 PM
 * To change this template use File | Settings | File Templates.
 */
public class S3LocationTest {
    @Test
    public void testS3Connection() throws S3ServiceException {
        AWSCredentials unpw = new AWSCredentials("xxx", "xxx");
        S3Service s3 = new RestS3Service(unpw);
        s3.getBucket("lkjsdflkjdsf");
    }
}
