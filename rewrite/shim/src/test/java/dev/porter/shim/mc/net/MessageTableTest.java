package dev.porter.shim.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** the id -> encoder/decoder/handler table behind the SimpleChannel bridge, with synthetic messages. */
class MessageTableTest {
    /** buffer stand-in: a pair of data streams. */
    static final class Buf {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        DataInputStream in;

        Buf reader() {
            in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            return this;
        }
    }

    record Ctx(String name) {}

    static class Ping {
        final int value;
        final String text;

        Ping(int value, String text) {
            this.value = value;
            this.text = text;
        }
    }

    static final class Move {
        final double x, y;

        Move(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class SubPing extends Ping {
        SubPing() { super(1, "sub"); }
    }

    private static void w(Ping p, Buf b) {
        try {
            b.out.writeInt(p.value);
            b.out.writeUTF(p.text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Ping rPing(Buf b) {
        try {
            return new Ping(b.in.readInt(), b.in.readUTF());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void w(Move m, Buf b) {
        try {
            b.out.writeDouble(m.x);
            b.out.writeDouble(m.y);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Move rMove(Buf b) {
        try {
            return new Move(b.in.readDouble(), b.in.readDouble());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void roundTripKeepsTheFields() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        t.register(3, Ping.class, MessageTableTest::w, MessageTableTest::rPing, (m, c) -> {});
        Buf b = new Buf();
        t.encode(new Ping(-42, "héllo"), b);
        Ping back = (Ping) t.decode(3, b.reader());
        assertEquals(-42, back.value);
        assertEquals("héllo", back.text);
    }

    @Test
    void dispatchGoesToTheHandlerOfTheId() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        List<String> log = new ArrayList<>();
        t.register(0, Ping.class, MessageTableTest::w, MessageTableTest::rPing,
            (m, c) -> log.add("ping " + m.value + "/" + m.text + "@" + c.get().name()));
        t.register(7, Move.class, MessageTableTest::w, MessageTableTest::rMove,
            (m, c) -> log.add("move " + m.x + "," + m.y + "@" + c.get().name()));
        assertEquals(2, t.size());

        Buf b = new Buf();
        t.encode(new Move(1.5, -2.25), b);
        assertEquals(7, t.idOf(new Move(0, 0)));
        assertTrue(t.receive(7, b.reader(), () -> new Ctx("server")));
        Buf b2 = new Buf();
        t.encode(new Ping(5, "x"), b2);
        assertTrue(t.receive(0, b2.reader(), () -> new Ctx("client")));
        assertEquals(List.of("move 1.5,-2.25@server", "ping 5/x@client"), log);
    }

    @Test
    void contextIsOnlyRequestedByTheHandler() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        int[] asked = {0};
        t.register(1, Ping.class, MessageTableTest::w, MessageTableTest::rPing, (m, c) -> {});
        Buf b = new Buf();
        t.encode(new Ping(1, "a"), b);
        Supplier<Ctx> s = () -> {
            asked[0]++;
            return new Ctx("n");
        };
        t.receive(1, b.reader(), s);
        assertEquals(0, asked[0]);
    }

    @Test
    void aNullDecodeIsNotDispatched() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        boolean[] called = {false};
        t.register(2, Ping.class, MessageTableTest::w, b -> null, (m, c) -> called[0] = true);
        assertFalse(t.receive(2, new Buf().reader(), () -> new Ctx("c")));
        assertFalse(called[0]);
    }

    @Test
    void subclassIsSentAsTheRegisteredClass() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        t.register(4, Ping.class, MessageTableTest::w, MessageTableTest::rPing, (m, c) -> {});
        assertEquals(4, t.idOf(new SubPing()));
    }

    @Test
    void unregisteredAndDuplicateAreRejected() {
        MessageTable<Buf, Ctx> t = new MessageTable<>();
        t.register(0, Ping.class, MessageTableTest::w, MessageTableTest::rPing, (m, c) -> {});
        assertThrows(IllegalArgumentException.class, () -> t.idOf(new Move(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> t.decode(9, new Buf().reader()));
        assertThrows(IllegalArgumentException.class,
            () -> t.register(0, Move.class, MessageTableTest::w, MessageTableTest::rMove, (m, c) -> {}));
        assertThrows(IllegalArgumentException.class,
            () -> t.register(1, Ping.class, MessageTableTest::w, MessageTableTest::rPing, (m, c) -> {}));
    }
}
