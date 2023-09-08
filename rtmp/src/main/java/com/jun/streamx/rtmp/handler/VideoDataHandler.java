package com.jun.streamx.rtmp.handler;

import com.jun.streamx.rtmp.constants.RtmpMessageType;
import com.jun.streamx.rtmp.entity.RtmpMessage;
import io.netty.channel.ChannelHandlerContext;

import java.util.Optional;

/**
 * {@link RtmpMessageType#VIDEO_DATA}
 *
 * @author Jun
 * @since 1.0.0
 */
@Handler(type = RtmpMessageType.VIDEO_DATA)
public class VideoDataHandler extends AbstractMessageHandler {

    @Override
    public void process(ChannelHandlerContext ctx, RtmpMessage msg) {
        var session = getSession(ctx);
        var keyFrame = session.getKeyFrame();
        final var streamKey = session.streamKey();

        // key frame
        if (keyFrame == null && msg.isKeyFrame()) {
            session.setKeyFrame(msg.copy());

            // 加入推流端
            publishers.put(session.streamKey(), ctx.channel());
        } else {
            Optional.ofNullable(subscribers.get(streamKey))
                    .ifPresent(channels -> channels.forEach(channel -> channel.writeAndFlush(msg.retain())));
        }
    }
}
