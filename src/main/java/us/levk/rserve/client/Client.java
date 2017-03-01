/*
 * The MIT License (MIT)
 * Copyright (c) 2017 lev.v.kuznetsov@gmail.com
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package us.levk.rserve.client;

import static java.net.URI.create;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.Executors.newWorkStealingPool;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Stream.of;
import static javax.websocket.ContainerProvider.getWebSocketContainer;
import static us.levk.rserve.client.tools.reflect.Classes.base;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import javax.websocket.DeploymentException;
import javax.websocket.WebSocketContainer;

import us.levk.jackson.rserve.RserveMapper;
import us.levk.rserve.client.protocol.commands.Assign;
import us.levk.rserve.client.protocol.commands.Command;
import us.levk.rserve.client.protocol.commands.Evaluate;
import us.levk.rserve.client.protocol.commands.Resolve;
import us.levk.rserve.client.websocket.Endpoint;

/**
 * Rserve client
 * 
 * @author levk
 */
public interface Client extends Closeable {

  /**
   * Welcome wagon regex
   */
  static final Pattern HANDSHAKE_PATTERN = compile ("Rsrv0103QAP1.*--------------.*", MULTILINE | DOTALL);

  /**
   * @param c
   *          command
   * @return promise
   */
  <T> CompletableFuture <T> execute (Command <T> c);

  /**
   * @param n
   *          name
   * @param v
   *          value
   * @return promise
   */
  default CompletableFuture <Void> assign (String n, Object v) {
    return execute (new Assign (n, v));
  }

  /**
   * @param n
   *          name
   * @param t
   *          type
   * @return promise
   */
  default <T> CompletableFuture <T> resolve (String n, Type t) {
    return execute (new Resolve <> (n, t));
  }

  /**
   * @param c
   *          code
   * @return promise
   */
  default CompletableFuture <Void> evaluate (String c) {
    return execute (new Evaluate (c));
  }

  /**
   * @param j
   *          job
   * @return promise
   */
  default <T> CompletableFuture <T> batch (T j) {
    return base (j.getClass ()).flatMap (c -> of (c.getDeclaredFields ()).filter (f -> {
      return f.isAnnotationPresent (us.levk.rserve.client.Resolve.class);
    })).reduce (base (j.getClass ()).reduce (base (j.getClass ()).flatMap (c -> of (c.getDeclaredFields ()).filter (f -> {
      return f.isAnnotationPresent (us.levk.rserve.client.Assign.class);
    })).reduce (completedFuture ((Void) null), (p, f) -> p.thenCompose (x -> {
      String n = f.getAnnotation (us.levk.rserve.client.Assign.class).value ();
      CompletableFuture <Void> r = new CompletableFuture <> ();
      try {
        assign ("".equals (n) ? f.getName () : n, f.get (j)).thenRun ( () -> r.complete (null));
      } catch (Exception e) {
        r.completeExceptionally (e);
      }
      return r;
    }), (x, y) -> {
      throw new UnsupportedOperationException ();
    }), (p, c) -> p.thenCompose (x -> {
      return evaluate (c.getAnnotation (R.class).value ());
    }), (x, y) -> {
      throw new UnsupportedOperationException ();
    }), (p, f) -> p.thenCompose (x -> {
      if (!f.isAccessible ()) f.setAccessible (true);
      String n = f.getAnnotation (us.levk.rserve.client.Resolve.class).value ();
      CompletableFuture <Void> r = new CompletableFuture <> ();
      resolve ("".equals (n) ? f.getName () : n, f.getGenericType ()).thenAccept (v -> {
        try {
          f.set (j, v);
          r.complete (null);
        } catch (Exception e) {
          r.completeExceptionally (e);
        }
      });
      return r;
    }), (x, y) -> {
      throw new UnsupportedOperationException ();
    }).thenApply (x -> j);
  }

  /**
   * Client builder
   * 
   * @author levk
   */
  static class Builder {
    /**
     * Object mapper
     */
    private final RserveMapper mapper;
    /**
     * Async provider
     */
    private final ExecutorService executor;

    /**
     * @param m
     *          mapper
     * @param e
     *          executor
     */
    private Builder (RserveMapper m, ExecutorService e) {
      mapper = m;
      executor = e;
    }

    /**
     * @param m
     *          mapper
     * @return copy of this builder with the specified mapper
     */
    public Builder with (RserveMapper m) {
      return new Builder (m, executor);
    }

    /**
     * @param e
     *          executor
     * @return copy of this builder with the executor specified
     */
    public Builder with (ExecutorService e) {
      return new Builder (mapper, e);
    }

    /**
     * @return websocket client builder
     */
    public WsBuilder websocket () {
      return websocket (getWebSocketContainer ());
    }

    /**
     * @param c
     *          container
     * @return websocket client builder
     */
    public WsBuilder websocket (WebSocketContainer c) {
      return new WsBuilder (c);
    }

    /**
     * Websocket client builder
     * 
     * @author levk
     */
    public class WsBuilder {
      /**
       * Container
       */
      private final WebSocketContainer container;

      /**
       * @param c
       *          container
       */
      private WsBuilder (WebSocketContainer c) {
        container = c;
      }

      /**
       * @param c
       *          container
       * @return copy of this builder with container specified
       */
      public WsBuilder with (WebSocketContainer c) {
        return new WsBuilder (c);
      }

      /**
       * @param u
       *          endpoint URI
       * @return client
       * @throws DeploymentException
       *           on connect
       * @throws IOException
       *           on connect
       */
      public Client connect (String u) throws DeploymentException, IOException {
        return connect (create (u));
      }

      /**
       * @param u
       *          endpoint URI
       * @return client
       * @throws DeploymentException
       *           on connect
       * @throws IOException
       *           on connect
       */
      public Client connect (URI u) throws DeploymentException, IOException {
        Endpoint e = new Endpoint (mapper, executor);
        container.connectToServer (e, u);
        return e;
      }
    }
  }

  /**
   * @return builder
   */
  public static Builder rserve () {
    return rserve (new RserveMapper ());
  }

  /**
   * @param m
   *          mapper
   * @return builder using the specified mapper
   */
  public static Builder rserve (RserveMapper m) {
    return rserve (m, newWorkStealingPool ());
  }

  /**
   * @param e
   *          executor
   * @return builder using the executor specified
   */
  public static Builder rserve (ExecutorService e) {
    return rserve (new RserveMapper (), e);
  }

  /**
   * @param m
   *          mapper
   * @param e
   *          executor
   * @return builder using the specified mapper and executor
   */
  public static Builder rserve (RserveMapper m, ExecutorService e) {
    return new Builder (m, e);
  }

  public static void main (String[] args) throws Exception {
//    try (Client c = rserve ().websocket ().connect ("ws://192.168.99.101:8081")) {
//      c.evaluate ("foo<-'bar'").get ();
//      Thread.sleep (10000);
//      c.resolve ("foo", String.class).get ();
//    }
     System.out.println (Base64.getEncoder ().encodeToString (new byte[] {
0x03, 0x00 , 0x00 , 0x00 , 0x0c , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00, 0x04 , 0x08 , 0x00 , 0x00 , 0x66 , 0x6f , 0x6f , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 }));
     System.out.println (Base64.getEncoder ().encodeToString (new byte[] {
0x03, 0x00 , 0x00 , 0x00 , 0x0c , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 }));
  }
}