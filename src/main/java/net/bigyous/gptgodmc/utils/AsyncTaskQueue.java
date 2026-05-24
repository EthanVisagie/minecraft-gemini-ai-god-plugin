package net.bigyous.gptgodmc.utils;

import net.bigyous.gptgodmc.interfaces.SimpFunction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

// takes in a simple synchronous functional interface and an object to act on
// and calls this function asynchronously in a thread pool on Function apply call
class AsyncTask<T, V> implements Function<ExecutorService, CompletableFuture<V>> {
    private SimpFunction<T> task;
    private T object;

    public AsyncTask(SimpFunction<T> task, T object) {
        this.task = task;
        this.object = object;
    }

    @Override
    public CompletableFuture<V> apply(ExecutorService pool) {
        CompletableFuture<V> theFuture = new CompletableFuture<>();
        pool.submit(() -> {
            try {
                this.task.run(object);
                theFuture.complete(null);
            } catch (Throwable e) {
                theFuture.completeExceptionally(e);
            }
        });

        return theFuture;
    }
}

// A task pool that uses asynchronous completion
// to chain a sequence of actions on separate threads
// intended behaviour is for insert to return immediately
// but for each item in queue to wait for its predecessor
// to return before executing
public class AsyncTaskQueue<T> {
    private SimpFunction<T> task;
    private ExecutorService pool;
    private CompletableFuture<Void> headFuture = null;

    public AsyncTaskQueue(SimpFunction<T> task) {
        this.task = task;
        this.pool = Executors.newCachedThreadPool();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void insert(T object) {
        AsyncTask t = new AsyncTask(task, object);
        
        // if the head is null we start the new head
        if(headFuture == null || headFuture.isDone()) {
            headFuture = t.apply(pool);
        } else {
            // if it was not null then we append to it
            headFuture = headFuture.thenCompose(_res -> {
                // do something here with previous result if desired
                // after headFuture completes this is the next one up:
                return t.apply(pool);
            });
        }
    }

    public void close() {
        this.pool.close();
    }
}

/*
 * Test code to prove that this queue behaves as expected:
package testAsync;

import java.io.Console;
import java.util.Random;

public class main {

	public static void main(String[] args) {
		AsyncTaskQueue<Integer> queue = new AsyncTaskQueue<Integer>((Integer i) -> {
			Random r = new Random();
			
			try {
				// sleep a random length
				Thread.sleep(r.nextInt(1000));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.printf("Completed %d\n", i);
			
		});
		
		// run the queue test
		for(int i = 0; i < 10; i++) {
			System.out.printf("Running %d\n", i);
			queue.insert(i);
		}
	}
}

 */
