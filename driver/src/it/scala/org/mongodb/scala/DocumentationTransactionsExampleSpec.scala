/*
 * Copyright 2017 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.scala

import org.mongodb.scala.model.{Filters, Updates}

import scala.concurrent.Await
import scala.concurrent.duration.Duration


//scalastyle:off magic.number
class DocumentationTransactionsExampleSpec extends RequiresMongoDBISpec {

  // Implicit functions that execute the Observable and return the results
  val waitDuration = Duration(5, "seconds")
  implicit class ObservableExecutor[T](observable: Observable[T]) {
    def execute(): Seq[T] = Await.result(observable.toFuture(), waitDuration)
  }

  implicit class SingleObservableExecutor[T](observable: SingleObservable[T]) {
    def execute(): T = Await.result(observable.toFuture(), waitDuration)
  }
  // end implicit functions

  "The Scala driver" should "be able to commit a transaction" in withClient { client =>
    assume(serverVersionAtLeast(List(4, 0, 0)) && !hasSingleHost())
    client.getDatabase("hr").drop().execute()

    // Start Example
    val database = client.getDatabase("hr")
    val employeesCollection = database.getCollection("employees")
    val eventsCollection = database.getCollection("events")

    val operationsObservable: Observable[ClientSession] = client.startSession().map(clientSession => {
        clientSession.startTransaction()
        employeesCollection.updateOne(clientSession, Filters.eq("employee", 3), Updates.set("status", "Inactive"))
        eventsCollection.insertOne(clientSession, Document("employee" -> 3, "status" -> Document("new" -> "Inactive", "old" -> "Active")))
        clientSession
      })

    val commitTransactionObservable: SingleObservable[Completed] =
      operationsObservable.flatMap(clientSession => clientSession.commitTransaction())

    val commitAndRetryObservable: SingleObservable[Completed] = commitTransactionObservable.recoverWith({
      case e: MongoException if e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) => {
        println("UnknownTransactionCommitResult, retrying commit operation ...")
        commitTransactionObservable
      }
      case e: Exception => {
        println("Exception during commit ...")
        throw e
      }
    })

    // End example
    commitAndRetryObservable.execute() should equal(Completed())
    database.drop().execute() should equal(Completed())
  }
}