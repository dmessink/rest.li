/*
   Copyright (c) 2016 LinkedIn Corp.

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

/**
 * $Id: $
 */

package com.linkedin.r2.transport.http.client;


import com.google.common.collect.Sets;
import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.Request;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.transport.common.bridge.common.TransportResponseImpl;
import com.linkedin.r2.transport.http.common.HttpProtocolVersion;
import com.linkedin.r2.util.Cancellable;
import com.linkedin.r2.util.Timeout;
import com.linkedin.r2.util.TimeoutRunnable;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Steven Ihde
 * @author Ang Xu
 * @author Zhenkai Zhu
 * @version $Revision: $
 */

/* package private */ class HttpNettyStreamClient extends AbstractNettyStreamClient
{
  static final Logger LOG = LoggerFactory.getLogger(HttpNettyStreamClient.class);

  private final ChannelPoolManager _channelPoolManager;

  /**
   * Creates a new HttpNettyStreamClient
   *
   * @param eventLoopGroup            The NioEventLoopGroup; it is the caller's responsibility to
   *                                  shut it down
   * @param executor                  An executor; it is the caller's responsibility to shut it down
   * @param poolSize                  Maximum size of the underlying HTTP connection pool
   * @param requestTimeout            Timeout, in ms, to get a connection from the pool or create one
   * @param idleTimeout               Interval after which idle connections will be automatically closed
   * @param shutdownTimeout           Timeout, in ms, the client should wait after shutdown is
   *                                  initiated before terminating outstanding requests
   * @param maxResponseSize           Maximum size of a HTTP response
   * @param sslContext                {@link SSLContext}
   * @param sslParameters             {@link SSLParameters}with overloaded construct
   * @param callbackExecutors         An optional EventExecutorGroup to invoke user callback
   * @param poolWaiterSize            Maximum waiters waiting on the HTTP connection pool
   * @param name                      Name of the {@link HttpNettyStreamClient}
   * @param jmxManager                A management class that is aware of the creation/shutdown event
   *                                  of the underlying {@link ChannelPoolManager}
   * @param strategy                  The strategy used to return pool objects.
   * @param minPoolSize               Minimum number of objects in the pool. Set to zero for no minimum.
   * @param maxHeaderSize             Maximum size of all HTTP headers
   * @param maxChunkSize              Maximum size of a HTTP chunk
   * @param maxConcurrentConnections  Maximum number of concurrent connection attempts the HTTP
   *                                  connection pool can make.
   */
  public HttpNettyStreamClient(NioEventLoopGroup eventLoopGroup,
      ScheduledExecutorService executor,
      int poolSize,
      long requestTimeout,
      long idleTimeout,
      long shutdownTimeout,
      long maxResponseSize,
      SSLContext sslContext,
      SSLParameters sslParameters,
      ExecutorService callbackExecutors,
      int poolWaiterSize,
      String name,
      AbstractJmxManager jmxManager,
      AsyncPoolImpl.Strategy strategy,
      int minPoolSize,
      int maxHeaderSize,
      int maxChunkSize,
      int maxConcurrentConnections,
      boolean tcpNoDelay)
  {
    super(eventLoopGroup, executor, requestTimeout, shutdownTimeout, callbackExecutors,
        jmxManager);

    _channelPoolManager = new ChannelPoolManager(
      new HttpNettyStreamChannelPoolFactoryImpl(
        poolSize,
        idleTimeout,
        poolWaiterSize,
        strategy,
        minPoolSize,
        tcpNoDelay,
        _scheduler,
        maxConcurrentConnections,
        sslContext,
        sslParameters,
        maxHeaderSize,
        maxChunkSize,
        maxResponseSize,
        eventLoopGroup),
      name + ChannelPoolManager.BASE_NAME);

    _jmxManager.onProviderCreate(_channelPoolManager);
  }

  public HttpNettyStreamClient(NioEventLoopGroup eventLoopGroup,
                               ScheduledExecutorService executor,
                               long requestTimeout,
                               long shutdownTimeout,
                               ExecutorService callbackExecutors,
                               AbstractJmxManager jmxManager,
                               ChannelPoolManager channelPoolManager)
  {
    super(eventLoopGroup, executor, requestTimeout, shutdownTimeout, callbackExecutors,
      jmxManager);
    _channelPoolManager = channelPoolManager;
    _jmxManager.onProviderCreate(_channelPoolManager);
  }

  /* Constructor for test purpose ONLY. */
  HttpNettyStreamClient(ChannelPoolFactory factory,
      ScheduledExecutorService executor,
      int requestTimeout,
      int shutdownTimeout,
      long maxResponseSize)
  {
    super(factory, executor, requestTimeout, shutdownTimeout);
    _channelPoolManager = new ChannelPoolManager(factory);
    _jmxManager.onProviderCreate(_channelPoolManager);
  }

  @Override
  public Map<String, PoolStats> getPoolStats()
  {
    return _channelPoolManager.getPoolStats();
  }

  @Override
  protected void doShutdown(Callback<None> callback)
  {
    final long deadline = System.currentTimeMillis() + _shutdownTimeout;
    TimeoutCallback<None> closeChannelsCallback = new ChannelPoolShutdownCallback(
        _scheduler, _shutdownTimeout, TimeUnit.MILLISECONDS, deadline, callback);
    _channelPoolManager.shutdown(closeChannelsCallback);
    _jmxManager.onProviderShutdown(_channelPoolManager);
  }

  @Override
  protected void doWriteRequest(Request request, RequestContext context, SocketAddress address,
      TimeoutTransportCallback<StreamResponse> callback)
  {
    final AsyncPool<Channel> pool;
    try
    {
      pool = _channelPoolManager.getPoolForAddress(address);
    }
    catch (IllegalStateException e)
    {
      errorResponse(callback, e);
      return;
    }

    context.putLocalAttr(R2Constants.HTTP_PROTOCOL_VERSION, HttpProtocolVersion.HTTP_1_1);

    TimeoutTransportCallbackConnectionAware<StreamResponse, Channel> newCallback = new TimeoutTransportCallbackConnectionAware<>(callback, callbacks, channel ->
    {
      if (channel != null)
      {
        channel.close();
      }
    });

    Callback<Channel> getCallback = new ChannelPoolGetCallback(pool, request, newCallback);

    final Cancellable pendingGet = pool.get(getCallback);
    if (pendingGet != null)
    {
      newCallback.addTimeoutTask(() -> pendingGet.cancel());
    }
  }

  Set<TimeoutTransportCallbackConnectionAware<StreamResponse, Channel>> callbacks = Sets.newConcurrentHashSet();

  private class ChannelPoolGetCallback implements Callback<Channel>
  {
    private final AsyncPool<Channel> _pool;
    private final Request _request;
    private final TimeoutTransportCallbackConnectionAware<StreamResponse, Channel> _callback;

    ChannelPoolGetCallback(AsyncPool<Channel> pool, Request request, TimeoutTransportCallbackConnectionAware<StreamResponse, Channel> callback)
    {
      _pool = pool;
      _request = request;
      _callback = callback;
    }


    @Override
    public void onSuccess(final Channel channel)
    {
      _callback.setConnection(channel);

      // This handler ensures the channel is returned to the pool at the end of the
      // Netty pipeline.
      channel.attr(ChannelPoolStreamHandler.CHANNEL_POOL_ATTR_KEY).set(_pool);
      _callback.addTimeoutTask(() -> {
        AsyncPool<Channel> pool = channel.attr(ChannelPoolStreamHandler.CHANNEL_POOL_ATTR_KEY).getAndRemove();
        if (pool != null)
        {
          pool.dispose(channel);
        }
      });

      Timeout<None> streamingTimeout = new Timeout<>(_scheduler, _requestTimeout, TimeUnit.MILLISECONDS, None.none());
      _callback.addTimeoutTask(() -> {
        Timeout<None> timeout = channel.attr(RAPResponseDecoder.TIMEOUT_ATTR_KEY).getAndRemove();
        if (timeout != null)
        {
          // stop the timeout for streaming since streaming of response would not happen
          timeout.getItem();
        }
      });

      // This handler invokes the callback with the response once it arrives.
      channel.attr(RAPStreamResponseHandler.CALLBACK_ATTR_KEY).set(_callback);
      channel.attr(RAPResponseDecoder.TIMEOUT_ATTR_KEY).set(streamingTimeout);

      State state = _state.get();
      if (state == HttpNettyStreamClient.State.REQUESTS_STOPPING || state == HttpNettyStreamClient.State.SHUTDOWN)
      {
        // In this case, we acquired a channel from the pool as request processing is halting.
        // The shutdown task might not timeout this callback, since it may already have scanned
        // all the channels for pending requests before we set the callback as the channel
        // attachment.  The TimeoutTransportCallback ensures the user callback in never
        // invoked more than once, so it is safe to invoke it unconditionally.
        _callback.onResponse(TransportResponseImpl.<StreamResponse>error(
            new TimeoutException("Operation did not complete before shutdown")));
        return;
      }

      // here we want the exception in outbound operations to be passed back through pipeline so that
      // the user callback would be invoked with the exception and the channel can be put back into the pool
      channel.writeAndFlush(_request).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void onError(Throwable e)
    {
      _callback.onResponse(TransportResponseImpl.<StreamResponse>error(e));
    }
  }

  private class ChannelPoolShutdownCallback extends TimeoutCallback<None>
  {
    public ChannelPoolShutdownCallback(ScheduledExecutorService scheduler, long timeout, TimeUnit timeoutUnit,
        long deadline, Callback<None> callback)
    {
      super(scheduler, timeout, timeoutUnit, new Callback<None>()
      {
        private void finishShutdown()
        {
          _state.set(HttpNettyStreamClient.State.REQUESTS_STOPPING);
          callbacks.forEach(callback -> errorResponse(callback, new TimeoutException("Operation did not complete before shutdown")));
          callbacks.forEach(TimeoutTransportCallbackConnectionAware::close);

          // Close all active and idle Channels
          new TimeoutRunnable(scheduler, deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS, () ->
          {
            _state.set(State.SHUTDOWN);
            LOG.info("Shutdown complete");
            callback.onSuccess(None.none());
          }, "Timed out waiting for channels to close, continuing shutdown").run();
        }

        @Override
        public void onSuccess(None none)
        {
          LOG.info("All connection pools shut down, closing all channels");
          finishShutdown();
        }

        @Override
        public void onError(Throwable e)
        {
          LOG.warn("Error shutting down HTTP connection pools, ignoring and continuing shutdown", e);
          finishShutdown();
        }
      }, "Connection pool shutdown timeout exceeded (" + _shutdownTimeout + "ms)");
    }
  }
}