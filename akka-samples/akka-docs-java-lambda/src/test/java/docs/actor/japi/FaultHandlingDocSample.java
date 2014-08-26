/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor.japi;

//#all
//#imports
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import akka.actor.*;
import akka.dispatch.Mapper;
import akka.event.LoggingReceive;
import akka.japi.pf.DeciderBuilder;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import scala.concurrent.duration.Duration;

import static akka.japi.Util.classTag;
import static akka.actor.SupervisorStrategy.restart;
import static akka.actor.SupervisorStrategy.stop;
import static akka.actor.SupervisorStrategy.escalate;

import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;

import static docs.actor.japi.FaultHandlingDocSample.WorkerApi.*;
import static docs.actor.japi.FaultHandlingDocSample.CounterServiceApi.*;
import static docs.actor.japi.FaultHandlingDocSample.CounterApi.*;
import static docs.actor.japi.FaultHandlingDocSample.StorageApi.*;

//#imports

public class FaultHandlingDocSample {

  /**
   * Runs the sample
   */
  public static void main(String[] args) {
    Config config = ConfigFactory.parseString(
      "akka.loglevel = \"DEBUG\"\n" +
      "akka.actor.debug {\n" +
      "  receive = on\n" +
      "  lifecycle = on\n" +
      "}\n");

    ActorSystem system = ActorSystem.create("FaultToleranceSample", config);
    ActorRef worker = system.actorOf(Props.create(Worker.class), "worker");
    ActorRef listener = system.actorOf(Props.create(Listener.class), "listener");
    // start the work and listen on progress
    // note that the listener is used as sender of the tell,
    // i.e. it will receive replies from the worker
    worker.tell(Start, listener);
  }

  /**
   * Listens on progress from the worker and shuts down the system when enough
   * work has been done.
   */
  public static class Listener extends AbstractLoggingActor {

    @Override
    public void preStart() {
      // If we don't get any progress within 15 seconds then the service
      // is unavailable
      context().setReceiveTimeout(Duration.create("15 seconds"));
    }

    public Listener() {
      receive(LoggingReceive.create(ReceiveBuilder.
        match(Progress.class, progress -> {
          log().info("Current progress: {} %", progress.percent);
          if (progress.percent >= 100.0) {
            log().info("That's all, shutting down");
            context().system().terminate();
          }
        }).
        matchEquals(ReceiveTimeout.getInstance(), x -> {
          // No progress within 15 seconds, ServiceUnavailable
          log().error("Shutting down due to unavailable service");
          context().system().terminate();
        }).build(), context()
      ));
    }
  }

  //#messages
  public interface WorkerApi {
    public static final Object Start = "Start";
    public static final Object Do = "Do";

    public static class Progress {
      public final double percent;

      public Progress(double percent) {
        this.percent = percent;
      }

      public String toString() {
        return String.format("%s(%s)", getClass().getSimpleName(), percent);
      }
    }
  }

  //#messages

  /**
   * Worker performs some work when it receives the Start message. It will
   * continuously notify the sender of the Start message of current Progress.
   * The Worker supervise the CounterService.
   */
  public static class Worker extends AbstractLoggingActor {
    final Timeout askTimeout = new Timeout(Duration.create(5, "seconds"));

    // The sender of the initial Start message will continuously be notified
    // about progress
    ActorRef progressListener;
    final ActorRef counterService = context().actorOf(
      Props.create(CounterService.class), "counter");
    final int totalCount = 51;

    // Stop the CounterService child if it throws ServiceUnavailable
    private static final SupervisorStrategy strategy =
      new OneForOneStrategy(DeciderBuilder.
        match(ServiceUnavailable.class, e -> stop()).
        matchAny(o -> escalate()).build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    public Worker() {
      receive(LoggingReceive.create(ReceiveBuilder.
        matchEquals(Start, x -> progressListener == null, x -> {
          progressListener = sender();
          context().system().scheduler().schedule(
            Duration.Zero(), Duration.create(1, "second"), self(), Do,
            context().dispatcher(), null
          );
        }).
        matchEquals(Do, x -> {
          counterService.tell(new Increment(1), self());
          counterService.tell(new Increment(1), self());
          counterService.tell(new Increment(1), self());
          // Send current progress to the initial sender
          pipe(ask(counterService, GetCurrentCount, askTimeout)
            .mapTo(classTag(CurrentCount.class))
            .map(new Mapper<CurrentCount, Progress>() {
              public Progress apply(CurrentCount c) {
                return new Progress(100.0 * c.count / totalCount);
              }
            }, context().dispatcher()), context().dispatcher())
            .to(progressListener);
        }).build(), context())
      );
    }
  }

  //#messages
  public interface CounterServiceApi {

    public static final Object GetCurrentCount = "GetCurrentCount";

    public static class CurrentCount {
      public final String key;
      public final long count;

      public CurrentCount(String key, long count) {
        this.key = key;
        this.count = count;
      }

      public String toString() {
        return String.format("%s(%s, %s)", getClass().getSimpleName(), key, count);
      }
    }

    public static class Increment {
      public final long n;

      public Increment(long n) {
        this.n = n;
      }

      public String toString() {
        return String.format("%s(%s)", getClass().getSimpleName(), n);
      }
    }

    public static class ServiceUnavailable extends RuntimeException {
      private static final long serialVersionUID = 1L;
      public ServiceUnavailable(String msg) {
        super(msg);
      }
    }

  }

  //#messages

  /**
   * Adds the value received in Increment message to a persistent counter.
   * Replies with CurrentCount when it is asked for CurrentCount. CounterService
   * supervise Storage and Counter.
   */
  public static class CounterService extends AbstractLoggingActor {

    // Reconnect message
    static final Object Reconnect = "Reconnect";

    private static class SenderMsgPair {
      final ActorRef sender;
      final Object msg;

      SenderMsgPair(ActorRef sender, Object msg) {
        this.msg = msg;
        this.sender = sender;
      }
    }

    final String key = self().path().name();
    ActorRef storage;
    ActorRef counter;
    final List<SenderMsgPair> backlog = new ArrayList<>();
    final int MAX_BACKLOG = 10000;

    // Restart the storage child when StorageException is thrown.
    // After 3 restarts within 5 seconds it will be stopped.
    private static final SupervisorStrategy strategy =
      new OneForOneStrategy(3, Duration.create("5 seconds"), DeciderBuilder.
       match(StorageException.class, e -> restart()).
       matchAny(o -> escalate()).build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    @Override
    public void preStart() {
      initStorage();
    }

    /**
     * The child storage is restarted in case of failure, but after 3 restarts,
     * and still failing it will be stopped. Better to back-off than
     * continuously failing. When it has been stopped we will schedule a
     * Reconnect after a delay. Watch the child so we receive Terminated message
     * when it has been terminated.
     */
    void initStorage() {
      storage = context().watch(context().actorOf(
        Props.create(Storage.class), "storage"));
      // Tell the counter, if any, to use the new storage
      if (counter != null)
        counter.tell(new UseStorage(storage), self());
      // We need the initial value to be able to operate
      storage.tell(new Get(key), self());
    }

    public CounterService() {
      receive(LoggingReceive.create(ReceiveBuilder.
        match(Entry.class, entry -> entry.key.equals(key) && counter == null, entry -> {
          // Reply from Storage of the initial value, now we can create the Counter
          final long value = entry.value;
          counter = context().actorOf(Props.create(Counter.class, key, value));
          // Tell the counter to use current storage
          counter.tell(new UseStorage(storage), self());
          // and send the buffered backlog to the counter
          for (SenderMsgPair each : backlog) {
            counter.tell(each.msg, each.sender);
          }
          backlog.clear();
        }).
        match(Increment.class, increment -> {
          forwardOrPlaceInBacklog(increment);
        }).
        matchEquals(GetCurrentCount, gcc -> {
          forwardOrPlaceInBacklog(gcc);
        }).
        match(Terminated.class, o -> {
          // After 3 restarts the storage child is stopped.
          // We receive Terminated because we watch the child, see initStorage.
          storage = null;
          // Tell the counter that there is no storage for the moment
          counter.tell(new UseStorage(null), self());
          // Try to re-establish storage after while
          context().system().scheduler().scheduleOnce(
            Duration.create(10, "seconds"), self(), Reconnect,
            context().dispatcher(), null);
        }).
        matchEquals(Reconnect, o -> {
          // Re-establish storage after the scheduled delay
          initStorage();
        }).build(), context())
      );
    }

    void forwardOrPlaceInBacklog(Object msg) {
      // We need the initial value from storage before we can start delegate to
      // the counter. Before that we place the messages in a backlog, to be sent
      // to the counter when it is initialized.
      if (counter == null) {
        if (backlog.size() >= MAX_BACKLOG)
          throw new ServiceUnavailable("CounterService not available," +
            " lack of initial value");
        backlog.add(new SenderMsgPair(sender(), msg));
      } else {
        counter.forward(msg, context());
      }
    }
  }

  //#messages
  public interface CounterApi {
    public static class UseStorage {
      public final ActorRef storage;

      public UseStorage(ActorRef storage) {
        this.storage = storage;
      }

      public String toString() {
        return String.format("%s(%s)", getClass().getSimpleName(), storage);
      }
    }
  }

  //#messages

  /**
   * The in memory count variable that will send current value to the Storage,
   * if there is any storage available at the moment.
   */
  public static class Counter extends AbstractLoggingActor {
    final String key;
    long count;
    ActorRef storage;

    public Counter(String key, long initialValue) {
      this.key = key;
      this.count = initialValue;

      receive(LoggingReceive.create(ReceiveBuilder.
        match(UseStorage.class, useStorage -> {
          storage = useStorage.storage;
          storeCount();
        }).
        match(Increment.class, increment -> {
          count += increment.n;
          storeCount();
        }).
        matchEquals(GetCurrentCount, gcc -> {
          sender().tell(new CurrentCount(key, count), self());
        }).build(), context())
      );
    }

    void storeCount() {
      // Delegate dangerous work, to protect our valuable state.
      // We can continue without storage.
      if (storage != null) {
        storage.tell(new Store(new Entry(key, count)), self());
      }
    }
  }

  //#messages
  public interface StorageApi {

    public static class Store {
      public final Entry entry;

      public Store(Entry entry) {
        this.entry = entry;
      }

      public String toString() {
        return String.format("%s(%s)", getClass().getSimpleName(), entry);
      }
    }

    public static class Entry {
      public final String key;
      public final long value;

      public Entry(String key, long value) {
        this.key = key;
        this.value = value;
      }

      public String toString() {
        return String.format("%s(%s, %s)", getClass().getSimpleName(), key, value);
      }
    }

    public static class Get {
      public final String key;

      public Get(String key) {
        this.key = key;
      }

      public String toString() {
        return String.format("%s(%s)", getClass().getSimpleName(), key);
      }
    }

    public static class StorageException extends RuntimeException {
      private static final long serialVersionUID = 1L;
      public StorageException(String msg) {
        super(msg);
      }
    }
  }

  //#messages

  /**
   * Saves key/value pairs to persistent storage when receiving Store message.
   * Replies with current value when receiving Get message. Will throw
   * StorageException if the underlying data store is out of order.
   */
  public static class Storage extends AbstractLoggingActor {

    final DummyDB db = DummyDB.instance;

    public Storage() {
      receive(LoggingReceive.create(ReceiveBuilder.
        match(Store.class, store -> {
          db.save(store.entry.key, store.entry.value);
        }).
        match(Get.class, get -> {
          Long value = db.load(get.key);
          sender().tell(new Entry(get.key, value == null ?
            Long.valueOf(0L) : value), self());
        }).build(), context())
      );
    }
  }

  //#dummydb
  public static class DummyDB {
    public static final DummyDB instance = new DummyDB();
    private final Map<String, Long> db = new HashMap<String, Long>();

    private DummyDB() {
    }

    public synchronized void save(String key, Long value) throws StorageException {
      if (11 <= value && value <= 14)
        throw new StorageException("Simulated store failure " + value);
      db.put(key, value);
    }

    public synchronized Long load(String key) throws StorageException {
      return db.get(key);
    }
  }
  //#dummydb
}
//#all
