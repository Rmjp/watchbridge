package dev.watchbridge.app

import org.junit.Test
import org.junit.Assert.*

class BluetoothProtocolTest {
    @Test fun staleSessionProofCannotAuthenticateReconnect(){assertTrue(BluetoothProof.matches("new-phone","new-watch","new-phone","new-watch"));assertFalse(BluetoothProof.matches("new-phone","new-watch","old-phone","old-watch"));assertFalse(BluetoothProof.matches("","","",""));assertFalse(BluetoothProof.matches("phone","watch","watch","phone"))}
    @Test fun fragmentedAndMultipleFrames(){val codec=BluetoothFrames();assertTrue(codec.feed("{\"a\":".toByteArray()).isEmpty());val frames=codec.feed("1}\n{\"b\":2}\n".toByteArray());assertEquals(listOf("{\"a\":1}","{\"b\":2}"),frames.map{String(it)})}
    @Test fun reconnectDiscardsPartialFrame(){val codec=BluetoothFrames();codec.feed("old partial".toByteArray());codec.reset();assertEquals("new",String(codec.feed("new\n".toByteArray()).single()))}
    @Test fun oversizeRejectedAndBufferReset(){val codec=BluetoothFrames(3);assertThrows(IllegalStateException::class.java){codec.feed("xxxx".toByteArray())};assertEquals("ok",String(codec.feed("ok\n".toByteArray()).single()))}
    @Test fun onlyMatchingAckReleasesWindow(){var now=0L;val w=DeliveryWindow{now};assertTrue(w.begin("a",100));assertFalse(w.begin("b",100));assertFalse(w.acknowledge("wrong"));assertEquals("a",w.id);now=31001;assertTrue(w.timedOut());assertTrue(w.acknowledge("a"));assertNull(w.id);assertTrue(w.begin("b",100))}
    @Test fun reconnectAllowsResendingSameDurableMessage(){val w=DeliveryWindow();assertTrue(w.begin("a",200));w.reset();assertTrue(w.begin("a",200));assertTrue(w.acknowledge("a"));assertFalse(w.acknowledge("a"))}
}
