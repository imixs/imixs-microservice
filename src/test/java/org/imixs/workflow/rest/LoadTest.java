package org.imixs.workflow.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.imixs.melman.BasicAuthenticator;
import org.imixs.melman.WorkflowClient;
import org.imixs.workflow.ItemCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * This test simulates multiple parallel workflow request in isolated threads.
 * 
 * The thread-pool is manged by the ExecuterService. The number of threads and
 * the request count per thread can be changed for different szenarios.
 * 
 * @see Issue https://github.com/imixs/imixs-workflow/issues/922
 */
public class LoadTest {

	static String BASE_URL = "http://localhost:8080/api";
	static String USERID = "admin";
	static String PASSWORD = "adminadmin";

	private IntegrationTest integrationTest = new IntegrationTest(BASE_URL);
	private static final int THREAD_COUNT = 10; // count of parallel threads
	private static final int REQUESTS_PER_THREAD = 5; // requests per Thread

	@BeforeEach
	public void setup() throws Exception {
		// Assumptions for integration tests
		assumeTrue(integrationTest.connected());
	}

	/**
	 * Instantiates a new Workflow Client object.
	 * 
	 * @return
	 */
	private WorkflowClient createClient() {
		WorkflowClient workflowClient = new WorkflowClient(BASE_URL);
		workflowClient.registerClientRequestFilter(new BasicAuthenticator(USERID, PASSWORD));
		return workflowClient;

	}

	@Test
	public void testLoad() throws InterruptedException {
		long startTime = System.currentTimeMillis();
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failureCount = new AtomicInteger(0);
		AtomicLong totalResponseTime = new AtomicLong(0);
		List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < THREAD_COUNT; i++) {
			executor.submit(() -> {
				// each thread works with its own client
				WorkflowClient threadWorkflowClient = createClient();
				for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
					long requestStart = System.currentTimeMillis();
					try {
						// create a simple workitem
						ItemCollection workitem = new ItemCollection();
						workitem.model("ticket-en-1.0").task(1000).event(10);
						workitem.replaceItemValue("type", "workitem");
						// add some data..
						workitem.replaceItemValue("_ticketid", "TEST-ID-001");
						workitem.replaceItemValue("_problem", "Just a JUnit Test");

						// process workitem
						workitem = threadWorkflowClient.processWorkitem(workitem);
						String uniqueID = workitem.getUniqueID();

						long requestTime = System.currentTimeMillis() - requestStart;
						totalResponseTime.addAndGet(requestTime);
						responseTimes.add(requestTime);

						System.out.println("Thread " + Thread.currentThread().getId() +
								" - Request " + j + " - ID: " + uniqueID);
						assertNotNull(uniqueID);
						successCount.incrementAndGet();
					} catch (Exception e) {
						System.err.println("Thread " + Thread.currentThread().getId() +
								" - Request failed: " + e.getMessage());
						failureCount.incrementAndGet();
					}
				}
			});
		}

		executor.shutdown();
		executor.awaitTermination(1, TimeUnit.MINUTES);

		// Print statistic result
		long totalTime = System.currentTimeMillis() - startTime;
		int totalRequests = THREAD_COUNT * REQUESTS_PER_THREAD;

		System.out.println("\n========== Test Statistics ==========");
		System.out.println("Total test time: " + totalTime + "ms");
		System.out.println("Total requests: " + totalRequests);
		System.out.println("Successful requests: " + successCount.get());
		System.out.println("Failed requests: " + failureCount.get());
		System.out.println("Success rate: " +
				(successCount.get() * 100.0 / totalRequests) + "%");

		if (!responseTimes.isEmpty()) {
			// Durchschnittliche Antwortzeit
			double avgResponseTime = totalResponseTime.get() / (double) successCount.get();
			System.out.println("Average response time: " + avgResponseTime + "ms");

			// Min/Max Antwortzeiten
			long minTime = Collections.min(responseTimes);
			long maxTime = Collections.max(responseTimes);
			System.out.println("Min response time: " + minTime + "ms");
			System.out.println("Max response time: " + maxTime + "ms");

			// Perzentile berechnen
			Collections.sort(responseTimes);
			System.out.println("50th percentile (median): " +
					responseTimes.get((int) (responseTimes.size() * 0.5)) + "ms");
			System.out.println("90th percentile: " +
					responseTimes.get((int) (responseTimes.size() * 0.9)) + "ms");
			System.out.println("95th percentile: " +
					responseTimes.get((int) (responseTimes.size() * 0.95)) + "ms");

			// Durchsatz (Requests pro Sekunde)
			double throughput = (successCount.get() * 1000.0) / totalTime;
			System.out.println("Throughput: " + String.format("%.2f", throughput) + " requests/second");
		}

		System.out.println("===================================");

		System.out.println("Load test completed. Success: " + successCount.get() +
				", Failures: " + failureCount.get());

		assertEquals(0, failureCount.get());
	}
}