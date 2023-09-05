package com.jun.streamx.broker.handler;

import com.jun.streamx.broker.constants.RtmpMessageType;
import com.jun.streamx.broker.entity.RtmpMessage;
import com.jun.streamx.broker.entity.amf0.*;
import com.jun.streamx.broker.exception.UnsupportedParamsException;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rtmp message handler
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
public class RtmpMessageHandler extends SimpleChannelInboundHandler<RtmpMessage> {
    //@formatter:off

    enum ClientType {
        publisher,
        subscriber
    }

    private static final String CLIENT_TYPE = "client-type";
    private static final String STREAM_KEY = "stream-key";
    private static final String FIRST_VIDEO_PACKET_KEY = "firstVideoPacketKey";
    /** k: app + stream name, v: 订阅客户端列表 */
    public static final Map<String, ChannelGroup> SUBSCRIBE = new ConcurrentHashMap<>();
    /** 推流 channel */
    public static final Map<String, Channel> PUBLISH = new ConcurrentHashMap<>();
    /** 当前 rtmp 所属 app */
    private String app;
    /** 当前 rtmp 所属 stream name */
    private String streamName;
    private RtmpMessage firstAudioPacket;
    private RtmpMessage firstVideoPacket;
    private Amf0Format metadata;
    private long latestVideoTimestamp;

    //@formatter:on

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("channel[{}] active", ctx.channel().id());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RtmpMessage msg) {
//        log.debug("收到 rtmp 报文: {}", msg);
        process(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("channel[{}] inactive", ctx.channel().id());

        // 移除 channel
        if (isPublisher(ctx)) {
            PUBLISH.remove(streamKey());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error(cause.getMessage(), cause);
        ctx.close();
    }


    private void process(ChannelHandlerContext ctx, RtmpMessage msg) {
        switch (msg.messageType()) {
            case AMF0_DATA -> handleAmf0Data(ctx, msg);
            case USER_CONTROL_MESSAGE -> handleUserControlMessage(ctx, msg);
            case AMF0_COMMAND -> handleAmf0Command(ctx, msg);
            case AUDIO_DATA -> handleAudioData(ctx, msg);
            case VIDEO_DATA -> handleVideoData(ctx, msg);
            default -> log.warn("un process msg type: {}", msg.messageType());
        }
    }

    private void handleAmf0Data(ChannelHandlerContext ctx, RtmpMessage msg) {
        // 解析
        var list = msg.payloadToAmf0();
        if (list.isEmpty()) {
            log.error("非法的 AMF0_DATA 报文: {}", msg);
            ctx.close();
            return;
        }

        // 我们需要 metadata
        for (int i = 0; i < list.size(); i++) {
            var amf0Format = list.get(i);
            if (amf0Format instanceof Amf0String s) {
                if ("onMetaData".equals(s.getValue())) {
                    // 表明下一个 Amf0Object(nginx-rtmp-module) 或 Amf0EcmaArray(obs)
                    metadata = list.get(i + 1);
                    break;
                }
            }
        }
    }

    private void handleUserControlMessage(ChannelHandlerContext ctx, RtmpMessage msg) {

    }

    private void handleAmf0Command(ChannelHandlerContext ctx, RtmpMessage msg) {
        // 解析出 cmd
        var list = msg.payloadToAmf0();
        if (list.isEmpty()) {
            log.error("非法的 AMF0_COMMAND 报文: {}", msg);
            ctx.close();
            return;
        }
        var amf0 = list.get(0).cast(Amf0String.class);
        var commandName = amf0.getValue();
        switch (commandName) {
            // NetConnection commands
            case "connect" -> {
                // The client sends the connect command to the server to request connection to a server application
                // instance.
                // command name + transaction id + command object + optional user arguments
                if (list.size() < 3) {
                    log.error("connect command structure error: {}", list);
                    ctx.close();
                }

                // transaction id
                var tid = list.get(1).cast(Amf0Number.class);

                // app name fetch
                var commandObject = list.get(2).cast(Amf0Object.class);
                this.app = commandObject.get("app").cast(Amf0String.class).getValue();

                // window acknowledgement size, set peer bandwidth, set chunk size
                var windowAcknowledgementSize = new RtmpMessage(
                        RtmpMessageType.WINDOW_ACKNOWLEDGEMENT_SIZE,
                        0, 0,
                        Unpooled.buffer(4).writeInt(5_000_000)
                );
                ctx.write(windowAcknowledgementSize);
                var setPeerBandwidth = new RtmpMessage(
                        RtmpMessageType.SET_PEER_BANDWIDTH,
                        0, 0,
                        Unpooled.buffer(5).writeInt(5_000_000).writeByte(2) // dynamic
                );
                ctx.write(setPeerBandwidth);
                var setChunkSize = new RtmpMessage(
                        RtmpMessageType.SET_CHUNK_SIZE,
                        0, 0,
                        Unpooled.buffer(4).writeInt(1480)
                );
                ctx.write(setChunkSize);

                // _result, structure is command name + transaction id + properties + information
                var buf = Unpooled.buffer();
                var amf0FormatList = buildConnectResult(tid);
                amf0FormatList.forEach(t -> t.write(buf));
                var _result = new RtmpMessage(
                        RtmpMessageType.AMF0_COMMAND,
                        0, 0,
                        buf
                );

                ctx.writeAndFlush(_result);
            }
            case "call" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "close" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "createStream" -> {
                // The client sends this command to the server to create a logical channel for message communication
                // The publishing of audio, video, and metadata is carried out over stream channel created using the
                // createStream command.
                //
                // NetConnection is the default communication channel, which has a stream ID 0. Protocol and a few
                // command messages, including createStream, use the default communication channel.

                // transaction id
                var tid = list.get(1).cast(Amf0Number.class);

                // _result
                var buf = Unpooled.buffer();
                var amf0FormatList = buildCreateStreamResult(tid);
                amf0FormatList.forEach(t -> t.write(buf));
                var _result = new RtmpMessage(
                        RtmpMessageType.AMF0_COMMAND,
                        0, 0,
                        buf
                );

                ctx.writeAndFlush(_result);
            }
            // NetStream commands
            case "play" -> {
                // The client sends this command to the server to play a stream. A playlist can also be created using
                // this command multiple times.
                //
                // If you want to create a dynamic playlist that switches among different live or recorded streams,
                // call play more than once and pass false for reset each time. Conversely, if you want to play the
                // specified stream immediately, clearing any other streams that are queued for play, pass true for reset.
                // channel 类型置入
                channelTypeAppend(ClientType.subscriber, ctx);

                // stream name 处理
                this.streamName = list.get(3).cast(Amf0String.class).getValue();

                // 找到对应的 publish 端
                var k = streamKey();
                var o = (HeadWrapper) PUBLISH.get(k).attr(AttributeKey.valueOf(STREAM_KEY)).get();

                // onStatus 响应
                var info = new Amf0Object();
                info.put("level", Amf0String.STATUS);
                info.put("code", new Amf0String("NetStream.Play.Start"));
                info.put("description", new Amf0String("Start publishing"));
                var amf0FormatList = List.of(
                        Amf0String.ON_STATUS,
                        new Amf0Number(0d),
                        Amf0Null.INSTANCE,
                        info
                );
                var buf = Unpooled.buffer();
                amf0FormatList.forEach(t -> t.write(buf));
                var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);
                ctx.write(onStatus);

                // |RtmpSampleAccess
                var sampleAccessBuf = Unpooled.buffer();
                List.of(
                        new Amf0String("|RtmpSampleAccess"),
                        Amf0Boolean.TRUE,
                        Amf0Boolean.TRUE
                ).forEach(t -> t.write(sampleAccessBuf));
                var sampleAccess = new RtmpMessage(
                        RtmpMessageType.AMF0_DATA,
                        0, 1, sampleAccessBuf
                );
                ctx.write(sampleAccess);

                // meta data 推送
                var onMetadataBuf = Unpooled.buffer();
                List.of(Amf0String.ON_METADATA, o.metadata).forEach(t -> t.write(onMetadataBuf));
                var onMetadata = new RtmpMessage(
                        RtmpMessageType.AMF0_DATA,
                        0, 0,
                        onMetadataBuf
                );
                ctx.write(onMetadata);

                // fist audio and video
                var video = new RtmpMessage(
                        o.firstVideo.messageType(),
                        latestVideoTimestamp,
                        o.firstVideo.streamId(),
                        o.firstVideo.payload().copy()
                );
                Optional.ofNullable(o.firstAudio.copy()).ifPresent(ctx::write);
                ctx.writeAndFlush(video).addListener(
                        future -> {
                            if (future.isSuccess()) {
                                // 加入拉流端
                                SUBSCRIBE.computeIfAbsent(streamKey(), unused -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
                                        .add(ctx.channel());
                            } else {
                                log.error("keyframe write failed: " + future.cause().getMessage(), future.cause());
                                ctx.close();
                            }
                        }
                );
            }
            case "play2" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "deleteStream" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "closeStream" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "receiveAudio" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "receiveVideo" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "publish" -> {
                // The client sends the publish command to publish a named stream to the server. Using this name,
                // any client can play this stream and receive the published audio, video, and data messages.
                // channel 类型置入
                channelTypeAppend(ClientType.publisher, ctx);

                // stream name fetch
                this.streamName = list.get(3).cast(Amf0String.class).getValue();

                // 返回 onStatus 响应
                var info = new Amf0Object();
                info.put("level", Amf0String.STATUS);
                info.put("code", new Amf0String("NetStream.Play.Start"));
                info.put("description", new Amf0String("Start publishing"));
                var amf0FormatList = List.of(
                        Amf0String.ON_STATUS,
                        Amf0Number.ZERO,
                        Amf0Null.INSTANCE,
                        info
                );
                var buf = Unpooled.buffer();
                amf0FormatList.forEach(t -> t.write(buf));
                var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);

                ctx.writeAndFlush(onStatus);
            }
            case "seek" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "pause" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            // 抓包发现的 command
            case "FCPublish" -> {
                var info = new Amf0Object();
                info.put("level", Amf0String.STATUS);
                info.put("code", new Amf0String("NetStream.Play.Start"));
                info.put("description", new Amf0String("Start publishing"));
                var amf0FormatList = List.of(
                        Amf0String.ON_FC_PUBLISH,
                        Amf0Number.ZERO,
                        Amf0Null.INSTANCE,
                        info
                );

                var buf = Unpooled.buffer();
                amf0FormatList.forEach(t -> t.write(buf));
                var rm = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);

                ctx.writeAndFlush(rm);
            }
        }
    }


    private void handleAudioData(ChannelHandlerContext ctx, RtmpMessage msg) {
        // 记录下第一个 audio data
        if (firstAudioPacket == null) {
            this.firstAudioPacket = msg.replaced();
        } else {
            Optional.ofNullable(SUBSCRIBE.get(streamKey()))
                    .ifPresent(channels -> {
                        channels.forEach(channel -> channel.writeAndFlush(msg.retain()));
                    });
        }
    }

    private void handleVideoData(ChannelHandlerContext ctx, RtmpMessage msg) {
        // 记录下第一个 key video data
        if (firstVideoPacket == null && msg.isKeyFrame()) {
            this.firstVideoPacket = msg.replaced();

            // 加入推流端
            var channel = ctx.channel();
            channel.attr(AttributeKey.valueOf(CLIENT_TYPE)).set(ClientType.publisher);
            PUBLISH.put(streamKey(), channel);
            ctx.channel().attr(AttributeKey.valueOf(STREAM_KEY))
                    .set(new HeadWrapper(metadata, firstAudioPacket, firstVideoPacket));
        } else {
            Optional.ofNullable(SUBSCRIBE.get(streamKey()))
                    .ifPresent(channels -> channels.forEach(channel -> channel.writeAndFlush(msg.retain())));
        }
    }

    private List<Amf0Format> buildConnectResult(Amf0Number tid) {
        var properties = new Amf0Object();
        properties.put("fmsVer", new Amf0String("FMS/3,0,1,123"));
        properties.put("capabilities", new Amf0Number(31d));
        var info = new Amf0Object();
        info.put("level", Amf0String.STATUS);
        info.put("code", new Amf0String("NetConnection.Connect.Success"));
        info.put("description", new Amf0String("Connection succeeded."));
        info.put("objectEncoding", Amf0Number.ZERO);
        return List.of(Amf0String._RESULT, tid, properties, info);
    }

    private List<Amf0Format> buildCreateStreamResult(Amf0Number tid) {
        return List.of(Amf0String._RESULT, tid, Amf0Null.INSTANCE, new Amf0Number(1d));
    }

    private String streamKey() {
        return String.format("%s/%s", app, streamName);
    }

    private boolean isPublisher(ChannelHandlerContext ctx) {
        return channelType(ctx) == ClientType.publisher;
    }

    private void channelTypeAppend(ClientType type, ChannelHandlerContext ctx) {
        ctx.channel().attr(AttributeKey.valueOf(CLIENT_TYPE)).set(type);
    }

    private ClientType channelType(ChannelHandlerContext ctx) {
        return (ClientType) ctx.channel().attr(AttributeKey.valueOf(CLIENT_TYPE)).get();
    }

    private record HeadWrapper(Amf0Format metadata, RtmpMessage firstAudio, RtmpMessage firstVideo) {
    }
}
