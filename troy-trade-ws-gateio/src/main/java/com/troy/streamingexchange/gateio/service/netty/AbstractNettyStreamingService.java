package com.troy.streamingexchange.gateio.service.netty;

import com.troy.commons.utils.ApplicationContextUtil;
import com.troy.commons.utils.DateUtils;
import com.troy.redis.RedisUtil;
import com.troy.streamingexchange.CommonUtil;
import com.troy.streamingexchange.gateio.dto.GateioWebsocketTypes;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.ScheduledFuture;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StopWatch;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public abstract class AbstractNettyStreamingService<T> {
    private final Logger LOG = LoggerFactory.getLogger(this.getClass());
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RETRY_DURATION = Duration.ofSeconds(15);

    private class Subscription {
        final ObservableEmitter<T> emitter;
        final String channelName;
        final Object[] args;

        public Subscription(ObservableEmitter<T> emitter, String channelName, Object[] args) {
            this.emitter = emitter;
            this.channelName = channelName;
            this.args = args;
        }
    }

    private final int maxFramePayloadLength;
    private final URI uri;
    private boolean isManualDisconnect = false;
    private Channel webSocketChannel;
    private Duration retryDuration;
    private Duration connectionTimeout;
    private final NioEventLoopGroup eventLoopGroup;
    protected Map<String, Subscription> channels = new ConcurrentHashMap<>();
    private boolean compressedMessages = false;

    public AbstractNettyStreamingService(String apiUrl) {
        this(apiUrl, 65536);
    }

    public AbstractNettyStreamingService(String apiUrl, int maxFramePayloadLength) {
        this(apiUrl, maxFramePayloadLength, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_RETRY_DURATION);
    }

    public AbstractNettyStreamingService(String apiUrl, int maxFramePayloadLength, Duration connectionTimeout, Duration retryDuration) {
        try {
            this.maxFramePayloadLength = maxFramePayloadLength;
            this.retryDuration = retryDuration;
            this.connectionTimeout = connectionTimeout;
            this.uri = new URI(apiUrl);
            this.eventLoopGroup = new NioEventLoopGroup();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Error parsing URI " + apiUrl, e);
        }
    }

    public Completable connect() {
        return Completable.create(completable -> {
            try {
                LOG.info("Connecting to {}://{}:{}{}", uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
                String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();

                String host = uri.getHost();
                if (host == null) {
                    throw new IllegalArgumentException("Host cannot be null.");
                }

                final int port;
                if (uri.getPort() == -1) {
                    if ("ws".equalsIgnoreCase(scheme)) {
                        port = 80;
                    } else if ("wss".equalsIgnoreCase(scheme)) {
                        port = 443;
                    } else {
                        port = -1;
                    }
                } else {
                    port = uri.getPort();
                }

                if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("Only WS(S) is supported.");
                }

                final boolean ssl = "wss".equalsIgnoreCase(scheme);
                final SslContext sslCtx;
                if (ssl) {
                    sslCtx = SslContextBuilder.forClient().build();
                } else {
                    sslCtx = null;
                }

                final WebSocketClientHandler handler = getWebSocketClientHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), maxFramePayloadLength),
                        this::messageHandler);

                Bootstrap b = new Bootstrap();
                b.group(eventLoopGroup)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, java.lang.Math.toIntExact(connectionTimeout.toMillis()))
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline p = ch.pipeline();
                                if (sslCtx != null) {
                                    p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                                }

                                WebSocketClientExtensionHandler clientExtensionHandler = getWebSocketClientExtensionHandler();
                                List<ChannelHandler> handlers = new ArrayList<>(4);
                                handlers.add(new HttpClientCodec());
                                if (compressedMessages) {
                                    handlers.add(WebSocketClientCompressionHandler.INSTANCE);
                                }
                                handlers.add(new HttpObjectAggregator(8192));

                                if (clientExtensionHandler != null) {
                                    handlers.add(clientExtensionHandler);
                                }

                                handlers.add(handler);
                                p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                                p.addLast(handlers.toArray(new ChannelHandler[handlers.size()]));
                            }
                        });

                b.connect(uri.getHost(), port).addListener((ChannelFuture future) -> {
                    webSocketChannel = future.channel();
                    if (future.isSuccess()) {
                        handler.handshakeFuture().addListener(f -> {
                            if (f.isSuccess()) {
                                completable.onComplete();
                            } else {
                                completable.onError(f.cause());
                            }
                        });
                    } else {
                        completable.onError(future.cause());
                    }

                });
            } catch (Exception throwable) {
                completable.onError(throwable);
            }
        });
    }

    public Completable disconnect() {
        isManualDisconnect = true;
        return Completable.create(completable -> {
            StopWatch stopWatch = new StopWatch("disconnect");
            if (null != webSocketChannel
                    && webSocketChannel.isOpen()) {
                stopWatch.start("webSocketChannel");
                CloseWebSocketFrame closeFrame = new CloseWebSocketFrame();
                webSocketChannel.writeAndFlush(closeFrame).addListener(future -> {
                    channels = new ConcurrentHashMap<>();
                    completable.onComplete();
                });
                stopWatch.stop();
                stopWatch.start("close");
                webSocketChannel.close();
                stopWatch.stop();
            }
            stopWatch.start("eventLoopGroup");
            eventLoopGroup.shutdownGracefully();
            stopWatch.stop();
            LOG.info("========================:::stopWatch:::{}", stopWatch);
        });
    }

    protected abstract String getChannelNameFromMessage(T message) throws IOException;

    public abstract String getSubscribeMessage(String channelName, Object... args) throws IOException;

    public abstract String getUnsubscribeMessage(String channelName) throws IOException;

    public String getSubscriptionUniqueId(String channelName, Object... args) {
        return channelName;
    }

    /**
     * Handler that receives incoming messages.
     *
     * @param message Content of the message from the server.
     */
    public abstract void messageHandler(String message);

    public void sendMessage(String message) {
        LOG.debug("Sending message: {}", message);

        if (webSocketChannel == null || !webSocketChannel.isOpen()) {
            LOG.warn("WebSocket is not open! Call connect first.");
            return;
        }

        if (!webSocketChannel.isWritable()) {
            LOG.warn("Cannot send data to WebSocket as it is not writable.");
            return;
        }

        if (message != null) {
            WebSocketFrame frame = new TextWebSocketFrame(message);
            webSocketChannel.writeAndFlush(frame);
        }
    }

    public Observable<T> subscribeChannel(String channelName, Object... args) {
        final String channelId = getSubscriptionUniqueId(channelName, args);
        LOG.info("Subscribing to channel {}", channelId);
        return Observable.<T>create(e -> {
            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                e.onError(new com.troy.streamingexchange.gateio.service.exception.NotConnectedException());
            }

            if (!channels.containsKey(channelId)) {
                Subscription newSubscription = new Subscription(e, channelName, args);
                channels.put(channelId, newSubscription);
                try {
                    sendMessage(getSubscribeMessage(channelName, args));
                } catch (IOException throwable) {
                    e.onError(throwable);
                }
            }
        }).doOnDispose(() -> {
            if (channels.containsKey(channelId)) {
                sendMessage(getUnsubscribeMessage(channelId));
                channels.remove(channelId);
            }
        }).share();

    }

    public void resubscribeChannels() {
        for (String channelId : channels.keySet()) {
            try {
                Subscription subscription = channels.get(channelId);
                String channelName = subscription.channelName;
                Object[] args = subscription.args;
                if (channelName.contains(GateioWebsocketTypes.ORDER.getSerializedValue())) {
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    String apiKey = args[0].toString();
                    String secretKeyBase64 = args[1].toString();
                    Long nonce = CommonUtil.getNonce();

                    String signChannelName = GateioWebsocketTypes.SERVER_SIGN.getSerializedValue() + "-" + apiKey;
                    LOG.info("auth Subscribing to channel {}", signChannelName);
                    String signature = CommonUtil.getSignature(secretKeyBase64, nonce);
                    try {
                        sendMessage(getSubscribeMessage(signChannelName, new Object[]{apiKey, signature, nonce}));
                        countDownLatch.await(3000, TimeUnit.MILLISECONDS);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (channelName.contains(GateioWebsocketTypes.SERVER_SIGN.getSerializedValue()) || channelName.contains(GateioWebsocketTypes.SERVER_PING.getSerializedValue())) {
                    continue;
                }
                sendMessage(getSubscribeMessage(subscription.channelName, subscription.args));
            } catch (IOException e) {
                LOG.error("Failed to reconnect channel: {}", channelId);
            }
        }
    }

    protected String getChannel(T message) {
        String channel;
        try {
            channel = getChannelNameFromMessage(message);
        } catch (IOException e) {
            LOG.error("Cannot parse channel from message: {}", message);
            return "";
        }
        return channel;
    }

    protected void handleMessage(T message) {
        String channel = getChannel(message);
        handleChannelMessage(channel, message);
    }

    protected void handleError(T message, Throwable t) {
        String channel = getChannel(message);
        handleChannelError(channel, t);
    }


    protected void handleChannelMessage(String channel, T message) {
        ObservableEmitter<T> emitter = channels.get(channel).emitter;
        if (emitter == null) {
            LOG.debug("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onNext(message);
    }

    protected void handleChannelError(String channel, Throwable t) {
        if (!channel.contains(channel)) {
            LOG.error("Unexpected channel's error: {}, {}.", channel, t);
            return;
        }
        ObservableEmitter<T> emitter = channels.get(channel).emitter;
        if (emitter == null) {
            LOG.debug("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onError(t);
    }

    protected WebSocketClientExtensionHandler getWebSocketClientExtensionHandler() {
        return WebSocketClientCompressionHandler.INSTANCE;
    }

    protected WebSocketClientHandler getWebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                                               WebSocketClientHandler.WebSocketMessageHandler handler) {
        return new NettyWebSocketClientHandler(handshaker, handler);
    }

    protected class NettyWebSocketClientHandler extends WebSocketClientHandler {
        protected NettyWebSocketClientHandler(WebSocketClientHandshaker handshaker, WebSocketMessageHandler handler) {
            super(handshaker, handler);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (isManualDisconnect) {
                isManualDisconnect = false;
            } else {
                try {
                    RedisUtil blockvcRedisUtil = ApplicationContextUtil.getBean(RedisUtil.class);
                    long retryCount = blockvcRedisUtil.increment("RetryWithDelay_gateio");
                    if(blockvcRedisUtil.ttl("RetryWithDelay_gateio") == -1){
                        blockvcRedisUtil.expire("RetryWithDelay_gateio", 5, TimeUnit.MINUTES);
                    }
                    if(retryCount <= 30){
                        super.channelInactive(ctx);
                        LOG.info("gate.io Reopening websocket because it was closed by the host");
        //                final Completable c = connect()
        //                        .doOnError(t -> LOG.warn("Problem with reconnect", t))
        //                        .retryWhen(new RetryWithDelay(5, retryDuration.toMillis()))
        //                        .doOnComplete(() -> {
        //                            LOG.info("Resubscribing channels");
        //                            resubscribeChannels();
        //                        });
        //                c.subscribe();
                        final EventLoop eventLoop = ctx.channel().eventLoop();
                        LOG.warn("gate.io ctx:{} reconnect starting {}", ctx, DateUtils.formatDateFull(new Date()));
                        ScheduledFuture<?> schedule = eventLoop.schedule(() -> {
                            Completable c = connect()
                                    .doOnError(t -> LOG.warn("gate.io Problem with websocket reconnect : {}", t.getMessage()))
                                    .doOnComplete(() -> {
                                        LOG.warn("gate.io Resubscribing channels");
                                        resubscribeChannels();
                                    });
                            c.subscribe();
                        }, 10, TimeUnit.SECONDS);
                    }else{
                        if(blockvcRedisUtil.ttl("RetryWithDelay_gateio") > 30) {
                            blockvcRedisUtil.expire("RetryWithDelay_gateio", 30 ,TimeUnit.SECONDS);
                        }
                    }
                } finally {
                    // 关闭重试前的连接
                    LOG.warn("reconnect close ctx");
                    ctx.close();
                }
            }
        }
    }

    public boolean isSocketOpen() {
        return webSocketChannel.isOpen();
    }

    public void useCompressedMessages(boolean compressedMessages) {
        this.compressedMessages = compressedMessages;
    }
}
