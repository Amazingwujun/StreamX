package com.jun.streamx.rtmp.handler;

import com.jun.streamx.rtmp.constants.RtmpMessageType;
import com.jun.streamx.rtmp.constants.UserControlMessageEvent;
import com.jun.streamx.rtmp.entity.RtmpMessage;
import com.jun.streamx.rtmp.entity.RtmpSession;
import com.jun.streamx.rtmp.entity.amf0.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * {@link RtmpMessageType#AMF0_COMMAND}
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Handler(type = RtmpMessageType.AMF0_COMMAND)
public class Amf0CommandHandler extends AbstractMessageHandler {

    @Override
    public void process(ChannelHandlerContext ctx, RtmpMessage msg) {
        // 解析出 cmd name
        var list = msg.payloadToAmf0();
        if (list.isEmpty()) {
            log.error("非法的 AMF0_COMMAND 报文: {}", msg);
            ctx.close();
            return;
        }
        var amf0 = list.get(0).cast(Amf0String.class);
        var commandName = amf0.getValue();

        // 区分处理 command
        switch (commandName) {
            // NetConnection commands
            case "connect" -> onConnect(ctx, list);
            case "call" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "close" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "createStream" -> onCreateStream(ctx, list);
            // NetStream commands
            case "play" -> onPlay(ctx, list);
            case "play2" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "deleteStream" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "closeStream" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "receiveAudio" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "receiveVideo" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "publish" -> onPublish(ctx, list);
            case "seek" -> throw new UnsupportedOperationException("不支持的 cmd: " + commandName);
            case "pause" -> onPause(ctx, list);
            // 抓包发现的 command
            case "FCPublish" -> onFCPublish(ctx, list);
        }
    }

    private void onConnect(ChannelHandlerContext ctx, List<Amf0Format> list) {
        // The client sends the connect command to the server to request connection to a server application
        // instance.
        // command name + transaction id + command object + optional user arguments
        if (list.size() < 3) {
            log.error("connect command structure error: {}", list);
            ctx.close();
        }

        // rtmp session build
        var session = newSession(ctx);

        // transaction id
        var tid = list.get(1).cast(Amf0Number.class);

        // app name fetch
        var commandObject = list.get(2).cast(Amf0Object.class);
        session.setApp(
                commandObject.get("app").cast(Amf0String.class).getValue()
        );

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
        buildConnectResult(tid).forEach(t -> t.write(buf));
        var _result = new RtmpMessage(
                RtmpMessageType.AMF0_COMMAND,
                0, 0,
                buf
        );

        ctx.writeAndFlush(_result);
    }

    private void onCreateStream(ChannelHandlerContext ctx, List<Amf0Format> list) {
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
        List.of(
                Amf0String._RESULT,
                tid,
                Amf0Null.INSTANCE,
                Amf0Number.ONE
        ).forEach(t -> t.write(buf));
        var _result = new RtmpMessage(
                RtmpMessageType.AMF0_COMMAND,
                0, 0,
                buf
        );

        ctx.writeAndFlush(_result);
    }

    private void onPlay(ChannelHandlerContext ctx, List<Amf0Format> list) {
        // The client sends this command to the server to play a stream. A playlist can also be created using
        // this command multiple times.
        //
        // If you want to create a dynamic playlist that switches among different live or recorded streams,
        // call play more than once and pass false for reset each time. Conversely, if you want to play the
        // specified stream immediately, clearing any other streams that are queued for play, pass true for reset.

        // stream name 处理
        var subscriberSession = getSession(ctx).setStreamName(
                list.get(3).cast(Amf0String.class).getValue()
        );
        final var streamKey = subscriberSession.streamKey();

        // channel 类型置入
        subscriberSession.setType(RtmpSession.Type.subscriber);

        // onStatus 响应
        var amf0FormatList = List.of(
                Amf0String.ON_STATUS,
                Amf0Number.ZERO,
                Amf0Null.INSTANCE,
                buildStatus("NetStream.Play.Start", "Start publishing")
        );
        var buf = Unpooled.buffer();
        amf0FormatList.forEach(t -> t.write(buf));
        var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);
        ctx.write(onStatus);

        // |RtmpSampleAccess
        var sampleAccessBuf = Unpooled.buffer();
        List
                .of(Amf0String.RTMP_SAMPLE_ACCESS, Amf0Boolean.TRUE, Amf0Boolean.TRUE)
                .forEach(t -> t.write(sampleAccessBuf));
        var sampleAccess = new RtmpMessage(
                RtmpMessageType.AMF0_DATA,
                0, 1, sampleAccessBuf
        );
        ctx.write(sampleAccess);

        // 找到对应的 publisher
        var publisherChannel = publishers.get(streamKey);
        if (publisherChannel == null) {
            log.warn("Stream[{}] 没有 publisher", streamKey);
            ctx.close();
            return;
        }
        final var publisherSession = getSession(publisherChannel);
        publisherSession.thenAccept(state -> {
            if (state != RtmpSession.State.complete) {
                log.warn("publisher state is {}", state);
                return;
            }

            // meta data
            // todo meta data 数据内容替换
            var onMetadataBuf = Unpooled.buffer();
            List
                    .of(Amf0String.ON_META_DATA, publisherSession.getMetadata())
                    .forEach(t -> t.write(onMetadataBuf));
            var onMetadata = new RtmpMessage(
                    RtmpMessageType.AMF0_DATA,
                    0, 0,
                    onMetadataBuf
            );
            ctx.write(onMetadata);

            // start stream push
            var keyFrame = publisherSession.getKeyFrame();
            var video = new RtmpMessage(
                    RtmpMessageType.VIDEO_DATA,
                    0,
                    keyFrame.streamId(),
                    keyFrame.payload().copy()
            );
            ctx.writeAndFlush(video).addListener(
                    future -> {
                        if (future.isSuccess()) {
                            // 加入拉流端
                            subscribers.computeIfAbsent(streamKey, k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
                                    .add(ctx.channel());
                        } else {
                            log.error("keyframe write failed: " + future.cause().getMessage(), future.cause());
                            ctx.close();
                        }
                    }
            );
        }).exceptionally(throwable -> {
            log.error("publisher session completeFailed: " + throwable.getMessage(), throwable);
            return null;
        });
    }

    private void onPublish(ChannelHandlerContext ctx, List<Amf0Format> list) {
        // The client sends the publish command to publish a named stream to the server. Using this name,
        // any client can play this stream and receive the published audio, video, and data messages.
        // channel 类型置入
        var session = getSession(ctx).setType(RtmpSession.Type.publisher);

        // stream name fetch
        session.setStreamName(
                list.get(3).cast(Amf0String.class).getValue()
        );

        // 返回 onStatus 响应
        var buf = Unpooled.buffer();
        List.of(
                Amf0String.ON_STATUS,
                Amf0Number.ZERO,
                Amf0Null.INSTANCE,
                buildStatus("NetStream.Play.Start", "Start publishing")
        ).forEach(t -> t.write(buf));
        var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);
        ctx.writeAndFlush(onStatus);
    }

    private void onFCPublish(ChannelHandlerContext ctx, List<Amf0Format> list) {
        var buf = Unpooled.buffer();
        List.of(
                Amf0String.ON_FC_PUBLISH,
                Amf0Number.ZERO,
                Amf0Null.INSTANCE,
                buildStatus("NetStream.Play.Start", "Start publishing")
        ).forEach(t -> t.write(buf));
        var rm = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);

        ctx.writeAndFlush(rm);
    }

    private void onPause(ChannelHandlerContext ctx, List<Amf0Format> list) {
        //The client sends the pause command to tell the server to pause or start playing.

        // Pause/Unpause flag
        var flag = list.get(3).cast(Amf0Boolean.class).isValue();

        // pause or unpause 逻辑
        if (flag) {
            // pause
            getSession(ctx).setPause(true);

            // onStatus 响应
            var amf0FormatList = List.of(
                    Amf0String.ON_STATUS,
                    Amf0Number.ZERO,
                    Amf0Null.INSTANCE,
                    buildStatus("NetStream.Pause.Notify", "Paused live")
            );
            var buf = Unpooled.buffer();
            amf0FormatList.forEach(t -> t.write(buf));
            var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);
            amf0FormatList.forEach(t -> t.write(buf));
            ctx.write(onStatus);

            // Stream EOF
            // The server sends this event to notify the client that the playback of data is over as requested on this
            // stream. No more data is sent without issuing additional commands. The client discards the messages
            // received for the stream. The 4 bytes of event data represent the ID of the stream on which playback
            // has ended.
            var streamEOF = new RtmpMessage(RtmpMessageType.USER_CONTROL_MESSAGE);
            streamEOF.payload().writeShort(UserControlMessageEvent.STREAM_EOF.val)
                    .writeInt(1); // 这里的 stream id 在业务上的用途我还没弄明白
            ctx.writeAndFlush(streamEOF);
        } else {
            // unpause
            // onStatus 响应
            var amf0FormatList = List.of(
                    Amf0String.ON_STATUS,
                    Amf0Number.ZERO,
                    Amf0Null.INSTANCE,
                    buildStatus("NetStream.Unpause.Notify", "Unpaused live")
            );
            var buf = Unpooled.buffer();
            amf0FormatList.forEach(t -> t.write(buf));
            var onStatus = new RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, buf);
            ctx.write(onStatus);

            // Stream Begin
            // The server sends this event to notify the client that the playback of data is over as requested on this
            // stream. No more data is sent without issuing additional commands. The client discards the messages
            // received for the stream. The 4 bytes of event data represent the ID of the stream on which playback
            // has ended.
            var streamBegin = new RtmpMessage(RtmpMessageType.USER_CONTROL_MESSAGE);
            streamBegin.payload().writeShort(UserControlMessageEvent.STREAM_BEGIN.val)
                    .writeInt(1); // 这里的 stream id 在业务上的用途我还没弄明白
            ctx.writeAndFlush(streamBegin);

            // key frame 写入
            var session = getSession(ctx);
            var publisherChannel = publishers.get(session.streamKey());
            if (publisherChannel == null) {
                log.warn("Stream {} linked publisher channel is null", session.streamKey());
                ctx.close();
                return;
            }
            var publisherSession = getSession(publisherChannel);
            publisherSession.thenAccept(state -> {
                if (state != RtmpSession.State.complete) {
                    log.warn("publisher state is {}", state);
                    return;
                }

                var keyFrame = publisherSession.getKeyFrame();
                var video = new RtmpMessage(
                        RtmpMessageType.VIDEO_DATA,
                        0,
                        keyFrame.streamId(),
                        keyFrame.payload().copy()
                );
                ctx.writeAndFlush(video).addListener(
                        future -> {
                            if (future.isSuccess()) {
                                // 加入拉流端
                                session.setPause(false);
                            } else {
                                log.error("keyframe write failed: " + future.cause().getMessage(), future.cause());
                                ctx.close();
                            }
                        }
                );
            }).exceptionally(throwable -> {
                log.error("publisher session completeFailed: " + throwable.getMessage(), throwable);
                return null;
            });
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

    private Amf0Object buildStatus(String code, String desc) {
        var info = new Amf0Object();
        info.put("level", Amf0String.STATUS);
        info.put("code", new Amf0String(code));
        info.put("description", new Amf0String(desc));
        return info;
    }
}
