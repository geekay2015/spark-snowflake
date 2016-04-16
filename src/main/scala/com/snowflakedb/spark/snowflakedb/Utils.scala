/*
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowflakedb.spark.snowflakedb

import java.net.URI
import java.sql.Connection
import java.util.UUID

import com.snowflakedb.spark.snowflakedb.Parameters.MergedParameters
import org.apache.spark.SparkContext

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.io._

import com.amazonaws.services.s3.{AmazonS3URI, AmazonS3Client}
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileSystem}
import org.slf4j.LoggerFactory

/**
 * Various arbitrary helper functions
 */
object Utils {

  private val log = LoggerFactory.getLogger(getClass)

  def classForName(className: String): Class[_] = {
    val classLoader =
      Option(Thread.currentThread().getContextClassLoader).getOrElse(this.getClass.getClassLoader)
    // scalastyle:off
    Class.forName(className, true, classLoader)
    // scalastyle:on
  }

  /**
   * Joins prefix URL a to path suffix b, and appends a trailing /, in order to create
   * a temp directory path for S3.
   */
  def joinUrls(a: String, b: String): String = {
    a.stripSuffix("/") + "/" + b.stripPrefix("/").stripSuffix("/") + "/"
  }

  /**
   * Snowflake COPY and UNLOAD commands don't support s3n or s3a, but users may wish to use them
   * for data loads. This function converts the URL back to the s3:// format.
   */
  def fixS3Url(url: String): String = {
    url.replaceAll("s3[an]://", "s3://")
  }

  /**
   * Returns a copy of the given URI with the user credentials removed.
   */
  def removeCredentialsFromURI(uri: URI): URI = {
    new URI(
      uri.getScheme,
      null, // no user info
      uri.getHost,
      uri.getPort,
      uri.getPath,
      uri.getQuery,
      uri.getFragment)
  }

  /**
   * Creates a randomly named temp directory path for intermediate data
   */
  def makeTempPath(tempRoot: String): String = Utils.joinUrls(tempRoot, UUID.randomUUID().toString)

  /**
   * Checks whether the S3 bucket for the given UI has an object lifecycle configuration to
   * ensure cleanup of temporary files. If no applicable configuration is found, this method logs
   * a helpful warning for the user.
   */
  def checkThatBucketHasObjectLifecycleConfiguration(
      tempDir: String,
      s3Client: AmazonS3Client): Unit = {
    if (tempDir.startsWith("file://")) {
      // Do nothing for file:
      return
    }

    try {
      val s3URI = new AmazonS3URI(Utils.fixS3Url(tempDir))
      val bucket = s3URI.getBucket
      val bucketLifecycleConfiguration = s3Client.getBucketLifecycleConfiguration(bucket)
      val key = Option(s3URI.getKey).getOrElse("")
      val someRuleMatchesTempDir = bucketLifecycleConfiguration.getRules.asScala.exists { rule =>
        // Note: this only checks that there is an active rule which matches the temp directory;
        // it does not actually check that the rule will delete the files. This check is still
        // better than nothing, though, and we can always improve it later.
        rule.getStatus == BucketLifecycleConfiguration.ENABLED && key.startsWith(rule.getPrefix)
      }
      if (!someRuleMatchesTempDir) {
        log.warn(s"The S3 bucket $bucket does not have an object lifecycle configuration to " +
          "ensure cleanup of temporary files. Consider configuring `tempdir` to point to a " +
          "bucket with an object lifecycle policy that automatically deletes files after an " +
          "expiration period. For more information, see " +
          "https://docs.aws.amazon.com/AmazonS3/latest/dev/object-lifecycle-mgmt.html")
      }
    } catch {
      case NonFatal(e) =>
        log.warn("An error occurred while trying to read the S3 bucket lifecycle configuration", e)
    }
  }

  /**
   * Given a URI, verify that the Hadoop FileSystem for that URI is not the S3 block FileSystem.
   * `spark-snowflakedb` cannot use this FileSystem because the files written to it will not be
   * readable by Snowflake (and vice versa).
   */
  def assertThatFileSystemIsNotS3BlockFileSystem(uri: URI, hadoopConfig: Configuration): Unit = {
    val fs = FileSystem.get(uri, hadoopConfig)
    // Note that we do not want to use isInstanceOf here, since we're only interested in detecting
    // exact matches. We compare the class names as strings in order to avoid introducing a binary
    // dependency on classes which belong to the `hadoop-aws` JAR, as that artifact is not present
    // in some environments (such as EMR). See #92 for details.
    if (fs.getClass.getCanonicalName == "org.apache.hadoop.fs.s3.S3FileSystem") {
      throw new IllegalArgumentException(
        "spark-snowflakedb does not support the S3 Block FileSystem. Please reconfigure `tempdir` to" +
        "use a s3n:// or s3a:// scheme.")
    }
  }

  // Reads a Map from a file using the format
  //     key = value
  // Snowflake-todo Use some standard config library
  def readMapFromFile(sc: SparkContext, file: String): Map[String, String] = {
    val fs = FileSystem.get(URI.create(file), sc.hadoopConfiguration)
    val is = fs.open(Path.getPathWithoutSchemeAndAuthority(new Path(file)))
    var src = scala.io.Source.fromInputStream(is)

    var map = new mutable.HashMap[String,String]
    for (line <- src.getLines()) {
      val tokens = line.split("=")
      val key = tokens(0).trim
      val value = tokens(1).trim
      map += (key -> value)
    }
    map.toMap
  }

  def getJDBCConnection(params: Map[String, String]): Connection = {
    var wrapper = new JDBCWrapper()
    val lcParams= params.map { case(key, value) => (key.toLowerCase, value)}
    var mergedParams = MergedParameters(lcParams)
    wrapper.getConnector(mergedParams)
  }
}