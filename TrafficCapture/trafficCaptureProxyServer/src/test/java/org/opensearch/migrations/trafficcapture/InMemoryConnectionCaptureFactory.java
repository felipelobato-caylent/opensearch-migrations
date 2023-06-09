package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InMemoryConnectionCaptureFactory implements IConnectionCaptureFactory {

    private final int bufferSize;

    @AllArgsConstructor
    public static class RecordedTrafficStream {
        public final byte[] data;
    }

    @Getter
    ConcurrentLinkedQueue<RecordedTrafficStream> recordedStreams = new ConcurrentLinkedQueue<>();

    public InMemoryConnectionCaptureFactory(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    private CompletableFuture closeHandler(ByteBuffer byteBuffer) {
        return CompletableFuture.runAsync(() -> {
            byte[] filledBytes = Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position());
            recordedStreams.add(new RecordedTrafficStream(filledBytes));
        });
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        // This array is only an indirection to work around Java's constraint that lambda values are final
        CompletableFuture[] singleAggregateCfRef = new CompletableFuture[1];
        singleAggregateCfRef[0] = CompletableFuture.completedFuture(null);
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteBufferMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId, () -> {
            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            var cos = CodedOutputStream.newInstance(bb);
            codedStreamToByteBufferMap.put(cos, bb);
            return cos;
        }, (codedOutputStream) -> {
            CompletableFuture cf = closeHandler(codedStreamToByteBufferMap.get(codedOutputStream));
            codedStreamToByteBufferMap.remove(codedOutputStream);
            singleAggregateCfRef[0] = singleAggregateCfRef[0].isDone() ? cf : CompletableFuture.allOf(singleAggregateCfRef[0], cf);
            return singleAggregateCfRef[0];
        });
    }
}