package com.smallsinger.spw.tags;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseWheelListener;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;

/** Lightweight pixel-based wheel scrolling shared by the workbench dialogs. */
final class SmoothScrollSupport {
  private static final String KEY =
      "com.smallsinger.spw.tags.smoothScroll";

  private SmoothScrollSupport() {}

  static void install(JScrollPane pane) {
    if (pane.getClientProperty(KEY) instanceof Scroller)
      return;
    Scroller scroller = new Scroller(pane);
    pane.putClientProperty(KEY, scroller);
    pane.setWheelScrollingEnabled(false);
    pane.addMouseWheelListener(scroller.listener);
  }

  static void scroll(JScrollPane pane, double rotation) {
    Object value = pane.getClientProperty(KEY);
    if (value instanceof Scroller scroller)
      scroller.scroll(rotation, false);
  }

  static void resetToStart(JScrollPane pane) {
    if (pane == null)
      return;
    Object value = pane.getClientProperty(KEY);
    if (value instanceof Scroller scroller)
      scroller.stop();
    pane.getVerticalScrollBar().setValue(
        pane.getVerticalScrollBar().getMinimum());
    pane.getHorizontalScrollBar().setValue(
        pane.getHorizontalScrollBar().getMinimum());
    pane.getViewport().setViewPosition(new java.awt.Point(0, 0));
  }
  static void disposeTree(Component component) {
    if (component instanceof JScrollPane pane) {
      Object value = pane.getClientProperty(KEY);
      if (value instanceof Scroller scroller)
        scroller.dispose();
    }
    if (component instanceof Container container)
      for (Component child : container.getComponents())
        disposeTree(child);
  }

  private static final class Scroller {
    private final JScrollPane pane;
    private final Timer timer;
    private final MouseWheelListener listener;
    private JScrollBar active;
    private double target;

    Scroller(JScrollPane pane) {
      this.pane = pane;
      timer = new Timer(TagWorkbenchWindow.frameDelay(pane),
                        event -> advance());
      timer.setCoalesce(true);
      listener = event -> {
        boolean horizontal = event.isShiftDown();
        if (scroll(event.getPreciseWheelRotation(), horizontal))
          event.consume();
      };
    }

    boolean scroll(double rotation, boolean horizontal) {
      timer.setDelay(TagWorkbenchWindow.frameDelay(pane));
      JScrollBar bar = horizontal ? pane.getHorizontalScrollBar()
                                  : pane.getVerticalScrollBar();
      if (!bar.isVisible() || bar.getMaximum() <= bar.getVisibleAmount()) {
        if (horizontal)
          return false;
        bar = pane.getHorizontalScrollBar();
        if (!bar.isVisible() || bar.getMaximum() <= bar.getVisibleAmount())
          return false;
      }
      if (bar != active || !timer.isRunning()) {
        active = bar;
        target = bar.getValue();
      }
      double distance = rotation * 42d;
      int maximum = Math.max(bar.getMinimum(),
                             bar.getMaximum() - bar.getVisibleAmount());
      target = Math.max(bar.getMinimum(),
                        Math.min(maximum, target + distance));
      if (!timer.isRunning())
        timer.start();
      return true;
    }

    private void advance() {
      if (active == null || active.getValueIsAdjusting()) {
        timer.stop();
        return;
      }
      double current = active.getValue();
      double difference = target - current;
      if (Math.abs(difference) < .8d) {
        active.setValue((int)Math.round(target));
        timer.stop();
        return;
      }
      double step = difference * .34d;
      if (Math.abs(step) < 1d)
        step = Math.copySign(1d, difference);
      active.setValue((int)Math.round(current + step));
    }

    private void stop() {
      timer.stop();
      active = null;
      target = 0;
    }
    private void dispose() {
      stop();
      pane.removeMouseWheelListener(listener);
      pane.setWheelScrollingEnabled(true);
      pane.putClientProperty(KEY, null);
    }
  }
}
