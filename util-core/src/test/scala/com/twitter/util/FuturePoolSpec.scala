package com.twitter.util

import com.twitter.conversions.time._
import java.util.concurrent.{Future => JFuture}
import java.util.concurrent._
import org.specs.SpecificationWithJUnit
import org.specs.mock.Mockito
import org.mockito.Matchers._

class FuturePoolSpec extends SpecificationWithJUnit with Mockito {
  val executor = Executors.newFixedThreadPool(1).asInstanceOf[ThreadPoolExecutor]
  val pool     = FuturePool(executor)

  "FuturePool" should {
    "dispatch to another thread" in {
      val source = new Promise[Int]
      val result = pool { source.get() } // simulate blocking call

      source.setValue(1)
      result.get() mustEqual 1
    }

    "Executor failing contains failures" in {
      val badExecutor = new ScheduledThreadPoolExecutor(1) {
        override def submit(runnable: Runnable): JFuture[_] = {
          throw new RejectedExecutionException()
        }
      }

      val pool = FuturePool(badExecutor)

      val runCount = new atomic.AtomicInteger(0)

      val result1 = pool {
        runCount.incrementAndGet()
      }

      runCount.get() mustEqual 0
    }

    "does not execute interrupted tasks" in {
      val runCount = new atomic.AtomicInteger

      val source1 = new Promise[Int]
      val source2 = new Promise[Int]

      val result1 = pool { runCount.incrementAndGet(); source1.get() }
      val result2 = pool { runCount.incrementAndGet(); source2.get() }

      result2.raise(new Exception)
      source1.setValue(1)

      // The executor will run the task for result 2, but the wrapper
      // in FuturePool will throw away the work if the future
      // representing the outcome has already been interrupted,
      // and will set the result to a CancellationException
      executor.getCompletedTaskCount must eventually(be_==(2))

      runCount.get() mustEqual 1
      result1.get()  mustEqual 1
      result2.get() must throwA[CancellationException]
    }

    "continue to run a task if it's interrupted while running" in {
      val runCount = new atomic.AtomicInteger

      val source = new Promise[Int]

      val startedLatch = new CountDownLatch(1)
      val cancelledLatch = new CountDownLatch(1)

      val result: Future[Int] = pool {
        try {
          startedLatch.countDown()
          runCount.incrementAndGet()
          cancelledLatch.await()
          throw new RuntimeException()
        } finally {
          runCount.incrementAndGet()
        }
        runCount.get
      }

      startedLatch.await(50.milliseconds)
      result.raise(new Exception)
      cancelledLatch.countDown()

      executor.getCompletedTaskCount must eventually(be_==(1))

      runCount.get() mustEqual 2
      result.get() must throwA[RuntimeException]
    }

    "returns exceptions that result from submitting a task to the pool" in {
      val executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue(1))
      val pool     = FuturePool(executor)

      val source   = new Promise[Int]
      val blocker1  = pool { source.get() } // occupy the thread
      val blocker2  = pool { source.get() } // fill the queue

      val rv = pool { "yay!" }

      rv.isDefined mustEqual true
      rv.get() must throwA[RejectedExecutionException]

      source.setValue(1)
    }
  }
}
