package toast.ui.view;

import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import toast.api.Core;
import toast.api.Process;
import toast.api.Processor;
import toast.enums.Palette;
import toast.event.ToastEvent;
import toast.event.scheduler.SchedulerFinishEvent;
import toast.event.scheduler.SchedulerStartEvent;
import toast.impl.ToastScheduler;
import toast.persistence.domain.ProcessorRecord;
import toast.persistence.domain.SchedulerRecord;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static toast.enums.Palette.*;

public class GanttChart extends Pane {

    private final Canvas canvas;
    private final ToastScheduler scheduler;
    private ScheduledFuture<?> thread = null;
    private final int timeSpan = 20;
    private int seed = 0;
    private double coreWidth;
    private double rowHeight;
    private double bottomHeight;
    private double timelineWidth;
    private Font coreFont;
    private Font processFont;
    private Font timelineFont;

    public GanttChart() {
        this.canvas = createCanvas();
        this.scheduler = ToastScheduler.getInstance();

        widthProperty().addListener(e -> setWidth(getWidth()));
        heightProperty().addListener(e -> setHeight(getHeight()));
        resize(canvas.getWidth(), canvas.getHeight());

        ToastEvent.registerListener(SchedulerStartEvent.class, (e) -> startPainting());
        ToastEvent.registerListener(SchedulerFinishEvent.class, (e) -> stopPainting());
    }

    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        resizeWidget(width, getPrefHeight());
    }

    @Override
    public void setHeight(double height) {
        super.setHeight(height);
        resizeWidget(getPrefWidth(), height);
    }

    private void startPainting() {
        this.seed = ThreadLocalRandom.current().nextInt(this.scheduler.getProcessList().size());
        this.thread = Executors.newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(this::repaint, 0L, 100L, TimeUnit.MILLISECONDS);
    }

    private void stopPainting() {
        this.thread.cancel(false);
    }

    private void repaint() {
        Platform.runLater(() -> {
            drawBackground();
            drawTimeline();
            drawProcessBars();
            drawCoreIndicators();
        });
    }

    private void resizeWidget(double width, double height) {
        setPrefSize(width, height);
        this.coreWidth = width * 0.15;
        this.rowHeight = height * 0.18;
        this.bottomHeight = rowHeight * 5;
        this.timelineWidth = width - this.coreWidth - 10;
        this.coreFont = Font.font(null, FontWeight.BOLD, height * 0.08);
        this.processFont = Font.font(null, FontWeight.BOLD, height * 0.07);
        this.timelineFont = Font.font(null, FontWeight.NORMAL, height * 0.07);
        this.canvas.setWidth(width);
        this.canvas.setHeight(height);
        repaint();
    }

    private void drawBackground() {
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        double width = this.canvas.getWidth();
        double height = this.canvas.getHeight();
        double xMax = getTimelineX(this.timeSpan);

        g.setFill(COMPONENT_BACKGROUND.color());
        g.fillRect(0, 0, width, height);

        g.setLineWidth(3);
        g.setStroke(STROKE.color());
        g.strokeLine(2, 2, 2, this.bottomHeight);
        g.strokeLine(2, 2, xMax, 2);
        g.strokeLine(2, this.bottomHeight, xMax, this.bottomHeight);

        for (int i = 1; i <= 4; i++) {
            g.strokeLine(2, this.rowHeight * i, xMax, this.rowHeight * i);
        }

        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        g.setFont(this.coreFont);
        g.setFill(TEXT_WIDGET.color());
        g.fillText("Arrival Time", this.coreWidth / 2.0, this.rowHeight / 2.0, this.coreWidth);
        g.setFill(TEXT_DISABLED.color());

        for (int i = 1; i <= 4; i++) {
            g.fillText("OFF", this.coreWidth / 2.0, (2 * i + 1) * this.rowHeight / 2.0, this.coreWidth);
        }
    }

    private void drawCoreIndicators() {
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        double x = this.coreWidth / 2.0;
        g.setFont(this.coreFont);
        g.setTextAlign(TextAlignment.CENTER);

        for (Processor processor : this.scheduler.getProcessorList()) {
            if (processor.isActive()) {
                Core core = processor.getCore();
                String name = core.getName();
                double y = getTimelineY(processor);
                g.setFill((core == Core.PERFORMANCE) ? P_CORE.color() : E_CORE.color());
                g.fillRect(3, y, this.coreWidth - 3, this.rowHeight);
                g.setFill(TEXT_WHITE.color());
                g.fillText(name, x, y + this.rowHeight / 2.0, this.coreWidth);
            }
        }
    }

    private void drawTimeline() {
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        g.setFont(this.timelineFont);
        g.setFill(TEXT_WIDGET.color());

        g.fillText(String.valueOf(getTime(0)), getTimelineX(0), this.bottomHeight + 10);
        g.fillText(String.valueOf(getTime(this.timeSpan)), getTimelineX(this.timeSpan), this.bottomHeight + 10);

        for (int i = 0; i <= this.timeSpan; i++) {
            double x = getTimelineX(i);
            g.fillText(String.valueOf(getTime(i)), x, this.bottomHeight + 10);

            if (i == 0 || i == this.timeSpan) {
                g.setStroke(STROKE.color());
                g.setLineWidth(3);
                g.setLineDashes();
            } else {
                g.setStroke(STROKE_LIGHT.color());
                g.setLineWidth(1);
                g.setLineDashes(5, 5);
            }

            g.strokeLine(x, 0, x, this.bottomHeight);
        }
    }

    private void drawProcessBars() {
        double delta = getTimelineDelta();
        GraphicsContext g = this.canvas.getGraphicsContext2D();
        g.setFont(this.processFont);

        for (Processor processor : this.scheduler.getProcessorList()) {
            ProcessorRecord record = SchedulerRecord.getInstance().getProcessorRecord(processor);
            Optional<Process> prev = Optional.empty();
            double timelineY = getTimelineY(processor);
            int length = 0;

            for (int i = 0; i < this.timeSpan; i++) {
                Optional<Process> now = record.getProcessAtTime(getTime(i));

                if (prev.isPresent() && !prev.equals(now)) {
                    double timelineX = getTimelineX(i) - delta * length;
                    double textX = getTimelineX(i) - delta * length / 2;
                    double textY = timelineY + this.rowHeight / 2;
                    Color barColor = getCoreColor(prev.get());
                    String text = String.valueOf(prev.get().getId());
                    g.setFill(barColor);
                    g.fillRect(timelineX, timelineY, delta * length, this.rowHeight);
                    g.setFill(Palette.getTextColor(barColor));
                    g.setTextAlign(TextAlignment.CENTER);
                    g.fillText(text, textX, textY, delta * length);
                    length = 0;
                }

                if (now.isPresent()) {
                    length++;
                }

                prev = now;
            }
        }
    }

    private Color getCoreColor(Process process) {
        Color color = process.isMission() ? P_CORE.color() : E_CORE.color();
        int variation = this.scheduler.getProcessList().size();
        double saturation = (double) ((this.seed + process.getId()) % variation) / variation;
        return color.deriveColor(1.0, saturation, 1.0, 1.0);
    }

    private double getTimelineX(int index) {
        if (index < 0 || index > this.timeSpan) {
            throw new IllegalArgumentException("Timeline index out of range: " + index);
        }

        double delta = getTimelineDelta();
        return this.coreWidth + delta * index;
    }

    private double getTimelineY(Processor processor) {
        return rowHeight * processor.getId();
    }

    private double getTimelineDelta() {
        return timelineWidth / timeSpan;
    }

    private int getTime(int index) {
        if (index < 0 || index > this.timeSpan) {
            throw new IllegalArgumentException("Timeline index out of range: " + index);
        }

        int time = this.scheduler.getElapsedTime();
        int offset = Math.max(time - this.timeSpan, 0);
        return index + offset;
    }

    private Canvas createCanvas() {
        double width = 800;
        double height = 230;
        Canvas canvas = new Canvas(width, height);
        setPrefSize(width, height);
        getChildren().add(canvas);
        return canvas;
    }
}
