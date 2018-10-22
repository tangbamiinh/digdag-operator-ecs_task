package pro.civitaspo.digdag.plugin.ecs_task.wait
import java.util.concurrent.{Executors, ExecutorService}

import com.amazonaws.services.ecs.AmazonECS
import com.amazonaws.services.ecs.model.{DescribeTasksRequest, DescribeTasksResult, Failure}
import com.amazonaws.services.ecs.waiters.DescribeTasksFunction
import com.amazonaws.waiters.{
  FixedDelayStrategy,
  MaxAttemptsRetryStrategy,
  PollingStrategy,
  Waiter,
  WaiterAcceptor,
  WaiterBuilder,
  WaiterParameters,
  WaiterState,
  WaiterTimedOutException,
  WaiterUnrecoverableException
}
import io.digdag.client.config.ConfigException
import io.digdag.util.DurationParam

import scala.collection.JavaConverters._

case class EcsTaskWaiter(
  ecs: AmazonECS,
  executorService: ExecutorService = Executors.newFixedThreadPool(50),
  timeout: DurationParam,
  condition: String,
  status: String,
  ignoreFailure: Boolean
) {

  def wait(req: DescribeTasksRequest): Unit = {
    newWaiter().run(new WaiterParameters[DescribeTasksRequest]().withRequest(req))
  }

  def shutdown(): Unit = {
    executorService.shutdown()
  }

  private def newWaiter(): Waiter[DescribeTasksRequest] = {
    new WaiterBuilder[DescribeTasksRequest, DescribeTasksResult]
      .withSdkFunction(new DescribeTasksFunction(ecs))
      .withAcceptors(newAcceptor())
      .withDefaultPollingStrategy(newPollingStrategy())
      .withExecutorService(executorService)
      .build()
  }

  private def newAcceptor(): WaiterAcceptor[DescribeTasksResult] = {
    val startAt: Long = System.currentTimeMillis()

    new WaiterAcceptor[DescribeTasksResult] {
      override def matches(output: DescribeTasksResult): Boolean = {
        val waitingMillis: Long = System.currentTimeMillis() - startAt
        if (waitingMillis > timeout.getDuration.toMillis) {
          throw new WaiterTimedOutException(s"Reached timeout ${timeout.getDuration.toMillis}ms without transitioning to the desired state")
        }
        if (!ignoreFailure) {
          val failures: Seq[Failure] = output.getFailures.asScala
          if (failures.nonEmpty) {
            throw new WaiterUnrecoverableException(s"Some tasks are failed: [${failures.map(_.toString).mkString(", ")}]")
          }
        }

        condition match {
          case "all" => output.getTasks.asScala.forall(t => t.getLastStatus.equals(status))
          case "any" => output.getTasks.asScala.exists(t => t.getLastStatus.equals(status))
          case _ => throw new ConfigException(s"condition: $condition is unsupported.")
        }
      }
      override def getState: WaiterState = WaiterState.SUCCESS
    }
  }

  private def newPollingStrategy(): PollingStrategy = {
    new PollingStrategy(
      new MaxAttemptsRetryStrategy(Int.MaxValue),
      new FixedDelayStrategy(1) // seconds
    )
  }

}
