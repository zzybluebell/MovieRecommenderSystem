package statistics

import org.apache.spark.SparkConf
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}

import java.io.File

object StatisticsRecommender {
  val METADATA_JSON ="Recommender/statisticsRecommender/src/main/resources/raw/metadata.json"
  val TAGS_JSON ="Recommender/statisticsRecommender/src/main/resources/raw/tags.json"
  val TAG_COUNT_JSON ="Recommender/statisticsRecommender/src/main/resources/raw/tag_count.json"
  val RATINGS_JSON ="Recommender/statisticsRecommender/src/main/resources/raw/ratings.json"
  val MOVIES_CSV ="Recommender/statisticsRecommender/src/main/resources/raw/movies.csv"
  val OUT_DIR ="Recommender/statisticsRecommender/Output/"
  val FORMAT = ".csv"
  val TOP10_RATING = "top10_rating"
  val LATEST10_DATE = "latest10_date"
  val MOST10_RATING = "most10_rating"
  val TAG_TOP_RATING = "tag_top_rating"

  case class Movie(mid: Long, title: String, rating: Double, time: String)

  def main(args: Array[String]): Unit = {
    val sc = new SparkConf().setMaster("local[*]").setAppName("StatisticsRecommender")
    val spark = SparkSession.builder().config(sc).getOrCreate()

    import spark.implicits._
    val movieDF = spark.read.json(METADATA_JSON)
      .select("item_id", "title", "avgRating", "dateAdded")
      .withColumnRenamed("item_id", "mid")
      .withColumnRenamed("avgRating", "rating")
      .withColumnRenamed("dateAdded", "time")
      .as[Movie]
      .toDF()

    // TOP 10 ratings
    val ratingDF = movieDF.select("rating").distinct().orderBy(desc("rating"))
      .limit(10)
      .withColumnRenamed("rating", "top10")
    val top10RatingsDF = movieDF
      .join(ratingDF, movieDF("rating") ===  ratingDF("top10"),"inner")
      .orderBy(desc("rating"))
      .select("rating", "title")
    top10RatingsDF.show()
    top10RatingsDF.coalesce(1).write.mode(SaveMode.Overwrite).csv(OUT_DIR + TOP10_RATING)
    writeSingleCSV(TOP10_RATING)

    // LATEST 10 dates
    val dateDF = movieDF
      .withColumn("date", split($"time", "T").getItem(0))
      .selectExpr("date").distinct().na.drop()
      .orderBy(desc("date")).limit(10)
    dateDF.show()
    val latest10DatesDF = movieDF
      .join(dateDF, split($"time", "T").getItem(0) ===  dateDF("date"),"inner")
      .orderBy(desc("date"))
      .select("date", "title")
    latest10DatesDF.show()
    latest10DatesDF.coalesce(1).write.mode(SaveMode.Overwrite).csv(OUT_DIR + LATEST10_DATE)
    writeSingleCSV(LATEST10_DATE)

    // MOST 10 ratings (count the number of users who gave a rating)
    val countDF = spark.read.json(RATINGS_JSON).groupBy("item_id")
      .count()
      .orderBy(desc("count"))
      .limit(10)
    val most10RatingsDF = movieDF
      .join(countDF, movieDF("mid") ===  countDF("item_id"),"inner")
      .orderBy(desc("count"))
      .select("count", "title")
    most10RatingsDF.show()
    most10RatingsDF.coalesce(1).write.mode(SaveMode.Overwrite).csv(OUT_DIR + MOST10_RATING)
    writeSingleCSV(MOST10_RATING)

    // highest rating among the SAME tag
    val tagsDF = spark.read.json(TAGS_JSON)
    val tagCountDF = spark.read.json(TAG_COUNT_JSON)
    val tagDF = tagCountDF
      .join(tagsDF, tagsDF("id") === tagCountDF("tag_id"), "inner")
      .select("item_id", "tag")
    val tagRatingDF = tagDF
      .join(movieDF, tagDF("item_id") ===  movieDF("mid"),"inner")
      .select( "tag", "rating", "title")
    val topRatingDF = tagRatingDF
      .groupBy("tag").max("rating")
      .withColumnRenamed("tag", "_tag")
    val tagTopRatingDF = tagRatingDF
      .join(topRatingDF, tagRatingDF("tag") === topRatingDF("_tag") && tagRatingDF("rating") ===  topRatingDF("max(rating)"),"inner")
      .select("tag", "rating", "title")
      .orderBy("tag")
    tagTopRatingDF.show()
    tagTopRatingDF.coalesce(1).write.mode(SaveMode.Overwrite).csv(OUT_DIR + TAG_TOP_RATING)
    writeSingleCSV(TAG_TOP_RATING)

    spark.stop()

  }

  // Refer to https://sparkbyexamples.com/spark/spark-write-dataframe-single-csv-file/
  def writeSingleCSV(output:String): Unit = {

    // Copy the actual file from Directory and Renames to custom name
    val hadoopConfig = new Configuration()
    val hdfs = FileSystem.get(hadoopConfig)

    hdfs.delete(new Path(OUT_DIR + output + FORMAT),true)
    val srcPath=new Path(OUT_DIR + output)
    val destPath= new Path(OUT_DIR + output + FORMAT)
    val srcFile=FileUtil.listFiles(new File(OUT_DIR + output))
      .filter(f=>f.getPath.endsWith(".csv"))(0)
    //Copy the CSV file outside of Directory and rename
    FileUtil.copy(srcFile,hdfs,destPath,true,hadoopConfig)
    //Removes CRC File that create from above statement
    hdfs.delete(new Path(OUT_DIR + "." + output + FORMAT + ".crc"),true)
    //Remove Directory created by df.write()
    hdfs.delete(srcPath,true)

  }


}
