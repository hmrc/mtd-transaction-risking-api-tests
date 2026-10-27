/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.api.specs.support

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.typesafe.config.ConfigFactory
import org.bson.Document
import org.bson.types.ObjectId

import java.util
import scala.jdk.CollectionConverters.*

final case class RetryWorkItem(
  id: ObjectId,
  status: String,
  failureCount: Int
)

final class RetryMongo extends AutoCloseable {
  private val connection = new ConnectionString(
    ConfigFactory.load().getString("local.retry-tests.mongodb-uri")
  )

  private val client   = MongoClients.create(connection)
  private val database = client.getDatabase(connection.getDatabase)

  def ping(): Unit = {
    database.runCommand(new Document("ping", 1))
    ()
  }

  def find(
    collection: String,
    correlationId: String
  ): Seq[RetryWorkItem] =
    database
      .getCollection(collection)
      .find(Filters.eq("item.correlationId", correlationId))
      .into(new util.ArrayList[Document]())
      .asScala
      .toSeq
      .map(document =>
        RetryWorkItem(
          document.getObjectId("_id"),
          document.getString("status"),
          document.get("failureCount", classOf[Number]).intValue()
        )
      )

  override def close(): Unit = client.close()

}
