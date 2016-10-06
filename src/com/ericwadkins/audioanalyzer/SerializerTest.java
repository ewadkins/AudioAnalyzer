package com.ericwadkins.audioanalyzer;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;

import java.io.*;

/**
 * Created by ericwadkins on 10/5/16.
 */
public class SerializerTest {

    private InputStream in = null;
    private OutputStream out = null;


    public SerializerTest() {
        super();
    }

    public static void main(String[] args) {
        SerializerTest comm = new SerializerTest();

        try {
            comm.connect("/dev/cu.usbmodem1421", 9600);
        } catch (Exception e) {
            e.printStackTrace();
        }

        final InputStream in = comm.getInputStream();
        final OutputStream out = comm.getOutputStream();

        Thread reader = new Thread(() -> {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    try {
                        System.out.println(line);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e2) {
                e2.printStackTrace();
            }
        });

        Thread writer = new Thread(() -> {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            DataOutputStream bos = new DataOutputStream(out);
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    try {
                        byte[] bytes = new byte[]{127 & 0xFF, 0, 64, 127};
                        bos.write(bytes);
                        //out.write(line.getBytes());
                        out.flush();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e2) {
                e2.printStackTrace();
            }
        });

        reader.start();
        writer.start();

        comm.start();
    }

    public void connect(String portName, int baudRate) throws Exception {
        CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portName);
        if (portIdentifier.isCurrentlyOwned()) {
            System.err.println("Error: Port is currently in use");
        }
        else {
            CommPort commPort = portIdentifier.open(this.getClass().getName(), 2000);

            if (commPort instanceof SerialPort) {
                SerialPort serialPort = (SerialPort) commPort;
                serialPort.setSerialPortParams(baudRate,
                        SerialPort.DATABITS_8, SerialPort.STOPBITS_1,SerialPort.PARITY_NONE);

                in = serialPort.getInputStream();
                out = serialPort.getOutputStream();

            }
            else {
                System.err.println("Error: Not a serial port");
            }
        }
    }

    public InputStream getInputStream() {
        if (in != null) {
            return in;
        }
        throw new IllegalStateException("A connection has not been established yet.");
    }

    public OutputStream getOutputStream() {
        if (out != null) {
            return out;
        }
        throw new IllegalStateException("A connection has not been established yet.");
    }

    public void start() {

        System.out.println("Hello World!");

    }

}
