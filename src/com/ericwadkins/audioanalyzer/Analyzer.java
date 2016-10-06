package com.ericwadkins.audioanalyzer;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by ericwadkins on 10/3/16.
 */
public class Analyzer {

    // Display settings
    public static final int WIDTH = 1024;
    public static final int HEIGHT = 375;
    public static final int COLOR_SPECTRUMS = 4;
    public static final int BASELINE = 30;

    public static final int DISPLAY_FPS = 10; // Lower if data is not showing properly

    // Audio settings
    public static final int SAMPLES_PER_SECOND = 8192; // Linearly shifts frequencies to the left (wider frequency range)
    public static final int UPDATES_PER_SECOND = 8; // Linearly decreases data points (-), decreases update time (+)

    // com.ericwadkins.audioanalyzer.Analyzer settings
    public static final double MAX_VALUE = 200.0;
    public static final double BASS_UPPER_BOUND = 0.04 * (8192.0 / SAMPLES_PER_SECOND);

    public static final int STACK_SIZE = 10;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public static final HashMap<Integer, Canvas> canvasMap = new HashMap<>();
    public static final HashMap<Integer, Frame> frameMap = new HashMap<>();
    public static int idCount = 0;
    public static int openCount = 0;

    public static long count = 0;

    public static void main(String args[]) {
        // Create format and get line info
        TargetDataLine line = null;
        AudioFormat format = new AudioFormat(SAMPLES_PER_SECOND, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Audio line not supported!");
        }

        // Obtain and open the line
        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }

        start(line, format);
    }

    public static void start(TargetDataLine line, AudioFormat format) {
        // Create display
        int display = createDisplay();

        // Create raw data array
        byte[] raw = new byte[(int) format.getSampleRate() * format.getFrameSize() / UPDATES_PER_SECOND];

        // Begin audio capture
        line.start();

        ArrayList<Frame> stack = new ArrayList<>(STACK_SIZE);
        while (true) {
            int n = line.read(raw, 0, raw.length);

            Frame frame = process(raw, stack);

            if (stack.size() == STACK_SIZE) {
                stack.remove(0);
            }
            stack.add(frame);

            updateDisplay(display, frame);
            count++;
            //System.out.println(count);
        }
    }

    public static Frame process(byte[] raw, ArrayList<Frame> stack) {
        Frame frame = new Frame();
        frame.stack = new ArrayList<>(stack);

        Frame last = null;
        if (stack.size() > 0) {
            last = stack.get(stack.size() - 1);
        }

        // Raw data
        frame.raw = raw;

        // Frequency data
        double[] frequencies = calculateFFT(raw);
        frame.frequencies = frequencies;

        // Processed data
        double[] processed = frequencies.clone();

        processed = applyFilter(processed, new double[]{1});
        processed = logScale(processed);
        processed = scaleRange(processed, 0, BASS_UPPER_BOUND, 0.5);
        for (int i = 0; i < stack.size(); i++) {
            processed = scaleSurrounding(processed, stack.get(i).peakAverage, 1 + Math.pow(0.5, stack.size() - i), 10);
        }

        frame.processed = processed;

        double sum = 0.0;
        double bassSum = 0.0;
        double maxIntensity = 0.0;
        int maxFrequency = 0;
        double maxBassIntensity = 0.0;
        for (int i = 0; i < processed.length; i++) {
            sum += processed[i];
            if (processed[i] > maxIntensity) {
                maxIntensity = processed[i];
                maxFrequency = i;
            }
            if (i < processed.length * BASS_UPPER_BOUND) {
                bassSum += processed[i];
                if (processed[i] > maxBassIntensity) {
                    maxBassIntensity = processed[i];
                }
            }
        }

        double[] peakFiltered = peakFilter(processed, 2);
        ArrayList<Integer> peakFrequencies = new ArrayList<>();
        ArrayList<Double> peakIntensities = new ArrayList<>();
        for (int i = 0; i < peakFiltered.length; i++) {
            if (peakFiltered[i] > 0) {
                peakFrequencies.add(i);
                peakIntensities.add(peakFiltered[i]);
            }
        }
        for (int i = 1; i < peakIntensities.size(); i++) {
            int j = i;
            while (j > 0 && peakIntensities.get(j - 1) < peakIntensities.get(j)) {
                double temp = peakIntensities.get(j);
                peakIntensities.set(j, peakIntensities.get(j - 1));
                peakIntensities.set(j - 1, temp);
                int temp2 = peakFrequencies.get(j);
                peakFrequencies.set(j, peakFrequencies.get(j - 1));
                peakFrequencies.set(j - 1, temp2);
                j--;
            }
        }
        double maxPeakIntensity = 0.0;
        for (int i = 0; i < peakFrequencies.size(); i++) {
            if (peakIntensities.get(i) > maxPeakIntensity
                    && peakFrequencies.get(i) > (double) processed.length * BASS_UPPER_BOUND) {
                maxPeakIntensity = peakIntensities.get(i);
            }
        }
        ArrayList<Integer> peaksList = new ArrayList<>();
        for (int i = 0; i < peakFrequencies.size(); i++) {
            if (peakIntensities.get(i) > 0.7 * maxPeakIntensity
                    && peakFrequencies.get(i) > (double) processed.length * BASS_UPPER_BOUND) {
                peaksList.add(peakFrequencies.get(i));
            }
        }
        int[] peaks = new int[peaksList.size()];
        for (int i = 0; i < peaksList.size(); i++) {
            peaks[i] = peaksList.get(i);
        }
        frame.peaks = peaks;

        double peakTotal = 0.0;
        double peakWeightedTotal = 0.0;
        for (int p = 0; p < peaks.length; p++) {
            int i = peaks[p];
            double mult = (double) (peaks.length - p) / peaks.length;
            peakTotal += processed[i] * mult;
            peakWeightedTotal += processed[i] * mult * i;
        }
        if (peakTotal < 10) {
            peakWeightedTotal = 0;
        }
        frame.peakAverage = (int) (peakWeightedTotal / peakTotal);


        frame.averageIntensity = sum / processed.length;
        frame.averageBassIntensity = bassSum / (processed.length * BASS_UPPER_BOUND);
        frame.maxIntensity = maxIntensity;
        frame.maxFrequency = maxFrequency;
        frame.maxBassIntensity = maxBassIntensity;

        if (last != null) {
            frame.averageIntensityDifference = frame.averageIntensity - last.averageIntensity;
            frame.averageIntensityGain = (frame.averageIntensityDifference) / last.averageIntensity;

            frame.averageBassIntensityDifference = frame.averageBassIntensity - last.averageBassIntensity;
            frame.averageBassIntensityGain = (frame.averageBassIntensityDifference) / last.averageBassIntensity;
            
            frame.maxIntensityDifference = frame.maxIntensity - last.maxIntensity;
            frame.maxIntensityGain = (frame.maxIntensityDifference) / last.maxIntensity;

            frame.maxBassIntensityDifference = frame.maxBassIntensity - last.maxBassIntensity;
            frame.maxBassIntensityGain = (frame.maxBassIntensityDifference) / last.maxBassIntensity;
        }

        //System.out.println(frame.maxBassIntensityGain);

        return frame;
    }

    public static double[] applyFilter(double[] data, double[] filter) {
        double mag = 0.0;
        for (int i = 0; i < filter.length; i++) {
            mag += Math.abs(filter[i]);
        }
        for (int i = 0; i < filter.length; i++) {
            filter[i] /= mag;
        }

        double[] processed = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            double sum = 0.0;
            for (int j = -filter.length / 2; j <= (filter.length - 1) / 2; j++) {
                if (i + j >= 0 && i + j < data.length) {
                    sum += data[i + j] * filter[j + filter.length / 2];
                }
                else if (i + j < 0){
                    sum += data[0] * filter[j + filter.length / 2];
                }
                else {
                    sum += data[data.length - 1] * filter[j + filter.length / 2];
                }
            }
            processed[i] = Math.abs(sum);
        }
        return processed;
    }

    public static double[] logScale(double[] data) {
        double[] processed = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            processed[i] = data[i] * Math.max(0, Math.log(i * 2));
        }
        return processed;
    }

    public static double[] scaleRange(double[] data, double low, double high, double mult) {
        double[] processed = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            if (i >= (int) (low * data.length) && i < (int) (high * data.length)) {
                processed[i] = data[i] * mult;
            }
            else {
                processed[i] = data[i];
            }
        }
        return processed;
    }

    public static double[] scaleSurrounding(double[] data, int center, double mult, double slope) {
        double[] processed = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            processed[i] = data[i] * Math.max(1, mult - Math.abs(center - i) / slope);
        }
        return processed;
    }

    public static double[] peakFilter(double[] data, int width) {
        double[] processed = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            boolean peak = true;
            for (int j = -width; j <= width; j++) {
                if (i + j >= 0 && i + j < data.length) {
                    if (j < 0 && data[i + j] > data[i] || j > 0 && data[i + j] > data[i]) {
                        peak = false;
                        break;
                    }
                }
            }
            if (peak) {
                processed[i] = data[i];
            }
        }
        return processed;
    }

    public static double[] calculateFFT(byte[] signal) {
        final int mNumberOfFFTPoints = signal.length / 2;
        double mMaxFFTSample = 0.0;

        double temp;
        Complex[] y;
        Complex[] complexSignal = new Complex[mNumberOfFFTPoints];
        double[] absSignal = new double[mNumberOfFFTPoints/2];

        for(int i = 0; i < mNumberOfFFTPoints; i++){
            temp = (double)((signal[2*i] & 0xFF) | (signal[2*i+1] << 8)) / 32768.0F;
            complexSignal[i] = new Complex(temp,0.0);
        }

        y = FFT.fft(complexSignal);

        for(int i = 0; i < (mNumberOfFFTPoints/2); i++) {
            absSignal[i] = Math.sqrt(Math.pow(y[i].re(), 2) + Math.pow(y[i].im(), 2));
            if(absSignal[i] > mMaxFFTSample) {
                mMaxFFTSample = absSignal[i];
            }
        }

        return absSignal;

    }

    public static int createDisplay(String title) {
        idCount++;
        openCount++;
        final int id = idCount;
        JFrame mainFrame = new JFrame(title == null ? "Audio com.ericwadkins.audioanalyzer.Analyzer " + id : title);
        mainFrame.setSize(WIDTH, HEIGHT + 22);
        mainFrame.setLayout(new GridLayout(3, 1));
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout());

        mainFrame.add(controlPanel);
        mainFrame.setVisible(true);
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        canvas.setSize(WIDTH, HEIGHT);
        controlPanel.add(canvas);

        mainFrame.addComponentListener(new ComponentListener() {
            @Override
            public void componentResized(ComponentEvent e) {
                //System.out.println(mainFrame.getWidth());
                //System.out.println(mainFrame.getHeight());
                //canvas.setSize(mainFrame.getWidth(), mainFrame.getHeight());
            }
            @Override
            public void componentMoved(ComponentEvent e) {
            }
            @Override
            public void componentShown(ComponentEvent e) {
            }
            @Override
            public void componentHidden(ComponentEvent e) {
            }
        });

        //mainFrame.pack();

        Graphics2D g = (Graphics2D) canvas.getGraphics();
        canvasMap.put(id, canvas);

        // Create display thread
        final Thread display = new Thread(new Runnable() {
            public void run() {
                boolean killed = false;
                while (!killed) {
                    if (frameMap.containsKey(id)) {
                        displayData(id, frameMap.get(id));
                    }
                    try {
                        Thread.sleep(1000 / DISPLAY_FPS);
                    } catch (InterruptedException e) {
                        killed = true;
                        openCount--;
                        if (openCount == 0) {
                            System.exit(0);
                        }
                    }
                }
            }
        });
        display.start();

        mainFrame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent windowEvent){
                display.interrupt(); // Use to kill thread
            }
        });

        return id;
    }

    public static int createDisplay() {
        return createDisplay(null);
    }

    public static void displayData(int id, Frame frame) {
        Graphics2D g = (Graphics2D) canvasMap.get(id).getGraphics();
        g.setStroke(new BasicStroke(2));
        g.clearRect(0, 0, WIDTH, HEIGHT);
        double xScale = (double) WIDTH / frame.processed.length;
        double yScale = (HEIGHT - BASELINE) / MAX_VALUE;
        int lastX = 0;
        int lastY = HEIGHT - BASELINE;
        //String style = "rect";
        String style = "line";

        //g.setColor(Color.getHSBColor(0.0f, 1.0f, 0.2f));
        //g.fillRect(0, 0, (int) (data.length * xScale * BASS_UPPER_BOUND), HEIGHT);

        if (frame.maxBassIntensityGain > 0.2 && frame.maxBassIntensityDifference > 10) {
            g.setColor(Color.getHSBColor(0.0f, 0.0f, (float) (0.2 * frame.maxBassIntensityDifference / 10)));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            System.out.print("Blink " + count + " ");
            for (int i = 0; i < 0.2 * frame.maxBassIntensityDifference; i++) {
                System.out.print("#");
            }
            System.out.println();
        }

        if (frame.maxBassIntensityGain > 5 && frame.maxBassIntensityDifference > 20) {
            g.setColor(Color.getHSBColor(0.0f, 1.0f, (float) (Math.max(0.4, 0.2 * frame.maxBassIntensityDifference / 10))));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }

        g.setColor(Color.getHSBColor(0.0f, 0.0f, 1.0f));
        g.drawRect(0, (HEIGHT - BASELINE), WIDTH, 1);

        g.setColor(Color.getHSBColor(0.0f, 0.0f, 0.5f));
        g.drawRect(0, (HEIGHT - BASELINE) - (int) ((frame.maxIntensity + 2) * yScale),
                (int) (frame.processed.length * xScale), 3);

        g.setColor(Color.getHSBColor(0.0f, 1.0f, 0.5f));
        g.drawRect(0, (HEIGHT - BASELINE) - (int) ((frame.maxBassIntensity + 2) * yScale),
                (int) (frame.processed.length * BASS_UPPER_BOUND * xScale), 3);

        g.setColor(Color.getHSBColor(0.0f, 0.0f, 0.5f));
        g.fillRect(0, (HEIGHT - BASELINE) - (int) ((frame.averageIntensity + 2) * yScale),
                (int) (frame.processed.length * xScale), 3);

        g.setColor(Color.getHSBColor(0.0f, 1.0f, 0.5f));
        g.fillRect(0, (HEIGHT - BASELINE) - (int) ((frame.averageBassIntensity + 2) * yScale),
                (int) (frame.processed.length * BASS_UPPER_BOUND * xScale), 3);

        for (int p = 0; p < frame.peaks.length; p++) {
            int i = frame.peaks[p];
            g.setColor(Color.getHSBColor(((float) i * COLOR_SPECTRUMS / frame.processed.length) % 1, 1.0f, 1.0f));
            g.fillRect((int) (i * xScale), HEIGHT - (int) (frame.processed[i] * yScale),
                    (int) xScale, BASELINE + (int) (frame.processed[i] * yScale));
        }

        g.setColor(Color.getHSBColor(((float) frame.peakAverage * COLOR_SPECTRUMS / frame.processed.length) % 1, 1.0f, 1.0f));
        g.fillRect((int) (frame.peakAverage * xScale), 0, (int) xScale, HEIGHT - BASELINE);
        //g.fillRect(0, 0, WIDTH, HEIGHT);

        for (int i = 0; i < frame.processed.length; i++) {
            g.setColor(Color.getHSBColor(((float) i * COLOR_SPECTRUMS / frame.processed.length) % 1, 1.0f, 1.0f));
            int x = (int) (i * xScale);
            int y = (int) ((HEIGHT - BASELINE) - Math.max(1, (int) (frame.processed[i] * yScale)));
            if (style == "line") {
                g.drawLine(lastX, lastY, x, y);
            }
            else if (style == "rect") {
                g.fillRect(x, y, (int) xScale, (HEIGHT - BASELINE) - y);
            }
            lastX = x;
            lastY = y;
        }
    }

    public static void updateDisplay(int id, Frame frame) {
        frameMap.put(id, frame);
    }

    public static void printData(double[] data) {
        for (double d : data) {
            System.err.print(d + ", ");
        }
        System.err.println();
    }

    public static void printData(byte[] data) {
        for (byte d : data) {
            System.err.print(d + ", ");
        }
        System.err.println();
    }

    static class Frame {
        public byte[] raw;
        public double[] frequencies;
        public double[] processed;
        public double averageIntensity;
        public double averageBassIntensity;
        public double maxIntensity;
        public double maxFrequency;
        public double maxBassIntensity;
        public double averageIntensityDifference;
        public double averageBassIntensityDifference;
        public double maxIntensityDifference;
        public double maxBassIntensityDifference;
        public double averageIntensityGain;
        public double averageBassIntensityGain;
        public double maxIntensityGain;
        public double maxBassIntensityGain;
        public int[] peaks;
        public int peakAverage;
        public ArrayList<Frame> stack;
    }

}
