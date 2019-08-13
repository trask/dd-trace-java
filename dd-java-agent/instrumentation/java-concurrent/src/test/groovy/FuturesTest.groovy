import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Note: ideally this should live with the rest of ExecutorInstrumentationTest,
 * but this code needs java8 so we put it here for now.
 */
class FuturesTest extends AgentTestRunner {

  Executor executor = Executors.newFixedThreadPool(3)

  def "guava future thenCompose.thenApply"() {
    setup:
    tracedMethod()
    sleep(1000)

    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    trace.size() == 3
    trace.get(1).parentId == trace.get(0).spanId
    trace.get(2).parentId == trace.get(0).spanId
  }

  def toCompletableFuture(ListenableFuture<Integer> listenableFuture) {
    final CompletableFuture<Integer> f = new CompletableFuture<Integer>() {
      @Override
      boolean cancel(boolean mayInterruptIfRunning) {
        boolean result = listenableFuture.cancel(mayInterruptIfRunning)
        super.cancel(mayInterruptIfRunning)
        return result
      }
    }

    Futures.addCallback(listenableFuture, new FutureCallback<Integer>() {
      @Override
      void onSuccess(Integer result) {
        f.complete(result)
      }

      @Override
      void onFailure(Throwable t) {
        f.completeExceptionally(t)
      }
    }, executor)

    return f
  }

  @Trace
  def tracedMethod() {
    return toCompletableFuture(new DummyListenableFuture(10))
      .thenCompose {
        new DummyListenableFuture(it)
      }
      .thenApply {
        println "Response: " + it
      }
  }

  class DummyListenableFuture implements ListenableFuture<Integer> {

    private final int seed

    DummyListenableFuture(int seed) {
      this.seed = seed
    }

    @Override
    void addListener(Runnable listener, Executor executor) {

    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
      return false
    }

    @Override
    boolean isCancelled() {
      return false
    }

    @Override
    boolean isDone() {
      return false
    }

    @Trace
    @Override
    Integer get() throws InterruptedException, ExecutionException {
      return this.seed
    }

    @Trace
    @Override
    Integer get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      return this.seed
    }
  }
}
