/*
Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding.platform

import com.twitter.scalding._

import org.scalatest.{ Matchers, WordSpec }

import scala.collection.JavaConverters._

class InAndOutJob(args: Args) extends Job(args) {
  Tsv("input").read.write(Tsv("output"))
}

object TinyJoinAndMergeJob {
  val peopleInput = TypedTsv[Int]("input1")
  val peopleData = List(1, 2, 3, 4)

  val messageInput = TypedTsv[Int]("input2")
  val messageData = List(1, 2, 3)

  val output = TypedTsv[(Int, Int)]("output")
  val outputData = List((1, 2), (2, 2), (3, 2), (4, 1))
}

class TinyJoinAndMergeJob(args: Args) extends Job(args) {
  import TinyJoinAndMergeJob._

  val people = peopleInput.read.mapTo(0 -> 'id) { v: Int => v }

  val messages = messageInput.read
    .mapTo(0 -> 'id) { v: Int => v }
    .joinWithTiny('id -> 'id, people)

  (messages ++ people).groupBy('id) { _.size('count) }.write(output)
}

object TsvNoCacheJob {
  val dataInput = TypedTsv[String]("fakeInput")
  val data = List("-0.2f -0.3f -0.5f", "-0.1f", "-0.5f")

  val throwAwayOutput = Tsv("output1")
  val typedThrowAwayOutput = TypedTsv[Float]("output1")
  val realOuput = Tsv("output2")
  val typedRealOutput = TypedTsv[Float]("output2")
  val outputData = List(-0.5f, -0.2f, -0.3f, -0.1f).sorted
}
class TsvNoCacheJob(args: Args) extends Job(args) {
  import TsvNoCacheJob._
  dataInput.read
    .flatMap(new cascading.tuple.Fields(Integer.valueOf(0)) -> 'word){ line: String => line.split("\\s") }
    .groupBy('word){ group => group.size }
    .mapTo('word -> 'num) { (w: String) => w.toFloat }
    .write(throwAwayOutput)
    .groupAll { _.sortBy('num) }
    .write(realOuput)
}

// Keeping all of the specifications in the same tests puts the result output all together at the end.
// This is useful given that the Hadoop MiniMRCluster and MiniDFSCluster spew a ton of logging.
class PlatformTests extends WordSpec with Matchers with HadoopPlatformTest {
  org.apache.log4j.Logger.getLogger("org.apache.hadoop").setLevel(org.apache.log4j.Level.ERROR)
  org.apache.log4j.Logger.getLogger("org.mortbay").setLevel(org.apache.log4j.Level.ERROR)

  "An InAndOutTest" should {
    val inAndOut = Seq("a", "b", "c")

    "reading then writing shouldn't change the data" in {
      HadoopPlatformJobTest(new InAndOutJob(_), cluster)
        .source("input", inAndOut)
        .sink[String]("output") { _.toSet shouldBe (inAndOut.toSet) }
        .run
    }
  }

  "A TinyJoinAndMergeJob" should {
    import TinyJoinAndMergeJob._

    "merge and joinWithTiny shouldn't duplicate data" in {
      HadoopPlatformJobTest(new TinyJoinAndMergeJob(_), cluster)
        .source(peopleInput, peopleData)
        .source(messageInput, messageData)
        .sink(output) { _.toSet shouldBe (outputData.toSet) }
        .run
    }
  }

  "A TsvNoCacheJob" should {
    import TsvNoCacheJob._

    "Writing to a tsv in a flow shouldn't effect the output" in {
      HadoopPlatformJobTest(new TsvNoCacheJob(_), cluster)
        .source(dataInput, data)
        .sink(typedThrowAwayOutput) { _.toSet should have size 4 }
        .sink(typedRealOutput) { _.map{ f: Float => (f * 10).toInt }.toList shouldBe (outputData.map{ f: Float => (f * 10).toInt }.toList) }
        .run
    }
  }
}

object IterableSourceDistinctJob {
  val data = List("a", "b", "c")
}

class IterableSourceDistinctJob(args: Args) extends Job(args) {
  import IterableSourceDistinctJob._

  TypedPipe.from(data ++ data ++ data).distinct.write(TypedTsv("output"))
}

class IterableSourceDistinctIdentityJob(args: Args) extends Job(args) {
  import IterableSourceDistinctJob._

  TypedPipe.from(data ++ data ++ data).distinctBy(identity).write(TypedTsv("output"))
}

class NormalDistinctJob(args: Args) extends Job(args) {
  TypedPipe.from(TypedTsv[String]("input")).distinct.write(TypedTsv("output"))
}

class IterableSourceDistinctTest extends WordSpec with Matchers with HadoopPlatformTest {
  "A IterableSource" should {
    import IterableSourceDistinctJob._

    "distinct properly from normal data" in {
      HadoopPlatformJobTest(new NormalDistinctJob(_), cluster)
        .source[String]("input", data ++ data ++ data)
        .sink[String]("output") { _.toList shouldBe data }
        .run
    }

    "distinctBy(identity) properly from a list in memory" in {
      HadoopPlatformJobTest(new IterableSourceDistinctIdentityJob(_), cluster)
        .sink[String]("output") { _.toList shouldBe data }
        .run
    }

    "distinct properly from a list" in {
      HadoopPlatformJobTest(new IterableSourceDistinctJob(_), cluster)
        .sink[String]("output") { _.toList shouldBe data }
        .run
    }
  }
}

class TypedPipeWithDescriptionJob(args: Args) extends Job(args) {
  TypedPipe.from[String](List("word1", "word1", "word2"))
    .withDescription("map stage - assign words to 1")
    .map { w => (w, 1L) }
    .group
    .withDescription("reduce stage - sum")
    .sum
    .withDescription("write")
    .write(TypedTsv[(String, Long)]("output"))
}

class WithDescriptionTest extends WordSpec with Matchers with HadoopPlatformTest {
  "A TypedPipeWithDescriptionPipe" should {
    "have a custom step name from withDescription" in {
      HadoopPlatformJobTest(new TypedPipeWithDescriptionJob(_), cluster)
        .inspectCompletedFlow { flow =>
          val steps = flow.getFlowSteps.asScala
          steps.map(_.getConfig.get(Config.StepDescriptions)) should contain ("map stage - assign words to 1, reduce stage - sum, write")
        }
        .run
    }
  }
}

class TypedPipeJoinWithDescriptionJob(args: Args) extends Job(args) {
  val x = TypedPipe.from[(Int, Int)](List((1, 1)))
  val y = TypedPipe.from[(Int, String)](List((1, "first")))
  val z = TypedPipe.from[(Int, Boolean)](List((2, true))).group

  x.hashJoin(y)
    .withDescription("hashJoin")
    .leftJoin(z)
    .withDescription("leftJoin")
    .values
    .write(TypedTsv[((Int, String), Option[Boolean])]("output"))
}

class JoinWithDescriptionTest extends WordSpec with Matchers with HadoopPlatformTest {
  "A TypedPipeJoinWithDescriptionPipe" should {
    "have a custom step name from withDescription" in {
      HadoopPlatformJobTest(new TypedPipeJoinWithDescriptionJob(_), cluster)
        .inspectCompletedFlow { flow =>
          val steps = flow.getFlowSteps.asScala
          steps should have size 1
          val firstStep = steps.headOption.map(_.getConfig.get(Config.StepDescriptions)).getOrElse("")
          firstStep should include ("leftJoin")
          firstStep should include ("hashJoin")
        }
        .run
    }
  }
}

class TypedPipeForceToDiskWithDescriptionJob(args: Args) extends Job(args) {
  val writeWords = {
    TypedPipe.from[String](List("word1 word2", "word1", "word2"))
      .withDescription("write words to disk")
      .flatMap { _.split("\\s+") }
      .forceToDisk
  }
  writeWords
    .groupBy(_.length)
    .withDescription("output frequency by length")
    .size
    .write(TypedTsv[(Int, Long)]("output"))
}

class ForceToDiskWithDescriptionTest extends WordSpec with Matchers with HadoopPlatformTest {
  "A TypedPipeForceToDiskWithDescriptionPipe" should {
    "have a custom step name from withDescription" in {
      HadoopPlatformJobTest(new TypedPipeForceToDiskWithDescriptionJob(_), cluster)
        .inspectCompletedFlow { flow =>
          val steps = flow.getFlowSteps.asScala
          val firstStep = steps.filter(_.getName.startsWith("(1/2"))
          val secondStep = steps.filter(_.getName.startsWith("(2/2"))
          firstStep.map(_.getConfig.get(Config.StepDescriptions)) should contain ("write words to disk")
          secondStep.map(_.getConfig.get(Config.StepDescriptions)) should contain ("output frequency by length")
        }
        .run
    }
  }
}
