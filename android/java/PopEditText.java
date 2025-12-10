// قيد التطوير..
package com.yn.tests.popedittext;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import android.widget.Toast;
import java.io.InputStream;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class PopEditText extends View {

    // paint & metrics
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lineHeight;
    private final float paddingLeft = 10f;

    // visual padding constants
    private static final float BOTTOM_SCROLL_OFFSET = 100f; // Visual padding below last line
    private static final float MIN_BOTTOM_VISIBLE_SPACE = 50f; // Minimum space to show below last line

    // scroll state (pixels)
    private float scrollY = 0f;
    private float scrollX = 0f;

    // sliding window
    private final List<String> linesWindow = new ArrayList<>();
    private int windowStartLine = 0;
    private int windowSize = 1000;
    private int prefetchLines = 500;

    // IO
    private final HandlerThread ioThread;
    private final Handler ioHandler;
    private BufferedReader readerForFile = null;
    private File sourceFile = null;
    private boolean isEof = false;
    private final AtomicInteger ioTaskVersion = new AtomicInteger(0);
    private boolean isFileCleared = false; // Track if the file content has been cleared

    // caches
    private final LinkedHashMap<Integer, String> modifiedLines = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Float> lineWidthCache;
    private final int LINE_WIDTH_CACHE_SIZE = 2000;
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;

    // caret
    private int cursorLine = 0;
    private int cursorChar = 0;

    // IME editable
    private final Editable imeEditable = android.text.Editable.Factory.getInstance().newEditable("");

    // composing
    private boolean hasComposing = false;
    private int composingLine = 0, composingOffset = 0, composingLength = 0;

    // selection
    private boolean hasSelection = false;
    private int selStartLine = 0, selStartChar = 0;
    private int selEndLine = 0, selEndChar = 0;
    private boolean selecting = false;
    private boolean isSelectAllActive = false;
    private boolean isEntireFileSelected = false; // Track if the entire file is logically selected

    // touch helpers
    private boolean pointerDown = false;
    private boolean movedSinceDown = false;
    private float downX = 0f, downY = 0f;
    private final int touchSlop;
    private boolean scrollerIsScrolling = false;

    // auto-scroll when dragging handles
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float autoScrollX = 0f, autoScrollY = 0f;
    private float lastTouchX = 0f, lastTouchY = 0f;

    // keyboard awareness
    private final Rect visibleDisplayFrame = new Rect();
    private int keyboardHeight = 0;

    // floating popup (custom)
    private boolean showPopup = false;
    private RectF popupRect = new RectF();
    private RectF btnCopyRect = new RectF();
    private RectF btnCutRect = new RectF();
    private RectF btnPasteRect = new RectF();
    private RectF btnSelectAllRect = new RectF();
    private float popupPadding = 12f;
    private float popupCorner = 16f;
    private float btnSpacing = 8f;
    private float btnHeight = 64f;
    private float btnWidth = 120f;

    // selection handles
    private RectF leftHandleRect = new RectF();
    private RectF rightHandleRect = new RectF();
    private RectF cursorHandleRect = new RectF();
    private float handleRadius = 30f;
    private int draggingHandle = 0;
    private volatile boolean isWindowLoading = false;

    private boolean isDisabled = false;
    private final AtomicInteger goToLineVersion = new AtomicInteger(0);

    // Loading circle variables
    private boolean showLoadingCircle = false;
    private float loadingCircleRadius = 40f;
    private int loadingCircleColor = 0xFF3F51B5; // Primary color (Material Design default)
    private float loadingCircleRotation = 0f;
    private ValueAnimator rotationAnimator;

    private final List<Long> lineOffsets = new ArrayList<>();
    private volatile boolean isIndexReady = false;

    // Variables for optimized scrolling
    private final Runnable delayedWindowCheck = new Runnable() {
        @Override
        public void run() {
            checkAndLoadWindow();
        }
    };

    public PopEditText(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        paint.setTextSize(36);
        paint.setColor(0xFF000000);
        paint.setAntiAlias(true);
        lineHeight = paint.getFontSpacing();

        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        scroller = new OverScroller(ctx);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) {
                    scroller.computeScrollOffset();
                    scrollX = scroller.getCurrX();
                    scrollY = scroller.getCurrY();
                    scroller.abortAnimation();
                }
                downX = e.getX();
                downY = e.getY();
                movedSinceDown = false;
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (movedSinceDown) return;
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - paddingLeft;
                int line = Math.max(0, (int) (y / lineHeight));
                ensureLineInWindow(line, true);
                String ln = getLineFromWindowLocal(line - windowStartLine);
                if (ln == null || ln.isEmpty()) {
                    onSingleTapUp(e);
                    return;
                }
                int charIndex = getCharIndexForX(ln, x);
                int[] bounds = computeWordBounds(ln, charIndex);
                selStartLine = selEndLine = line;
                selStartChar = bounds[0];
                selEndChar = bounds[1];
                hasSelection = true;
                isSelectAllActive = false;
                selecting = true;
                cursorLine = line;
                cursorChar = selEndChar;
                showPopupAtSelection();
                invalidate();

                // Show keyboard and automatically scroll to ensure cursor is visible
                showKeyboard();
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (hasSelection) {
                    hasSelection = false;
                    isSelectAllActive = false;
                }
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - paddingLeft;
                int line = Math.max(0, (int) (y / lineHeight));

                if (isEof && line >= windowStartLine + linesWindow.size() && !linesWindow.isEmpty()) {
                    cursorLine = windowStartLine + linesWindow.size() - 1;
                    String lastLineText = getLineFromWindowLocal(cursorLine - windowStartLine);
                    if (lastLineText == null) lastLineText = "";
                    cursorChar = lastLineText.length();
                } else {
                    ensureLineInWindow(line, true);
                    String ln = getLineFromWindowLocal(line - windowStartLine);
                    if (ln == null) ln = "";
                    int charIndex = getCharIndexForX(ln, x);
                    cursorLine = line;
                    cursorChar = Math.max(0, Math.min(charIndex, ln.length()));
                }

                hidePopup();
                selecting = false;
                invalidate();

                // Show keyboard and automatically scroll to ensure cursor is visible
                showKeyboard();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                movedSinceDown = true;
                scrollY += distanceY;
                scrollX += distanceX;
                clampScrollY();
                clampScrollX();

                removeCallbacks(delayedWindowCheck);
                postDelayed(delayedWindowCheck, 75); // Reduce delay for more responsive loading

                if (showPopup) hidePopup();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                int startX = Math.round(scrollX);
                int startY = Math.round(scrollY);
                int minX = 0;
                int maxX = Math.max(0, Math.round(getMaxLineWidthInWindow() - (getWidth() - paddingLeft)));
                int minY = 0;
                float maxScrollYFloat;
                if (isEof) {
                    // When keyboard is visible, ensure the last line stays above the keyboard with padding
                    float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();
                    float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
                    maxScrollYFloat = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight - (effectiveHeight - paddingToUse));
                } else {
                    // Use consistent bottom padding with clampScrollY
                    float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();
                    maxScrollYFloat = Math.max(0f, (windowStartLine + linesWindow.size() + windowSize) * lineHeight - (effectiveHeight - MIN_BOTTOM_VISIBLE_SPACE));
                }
                int maxY = Math.max(0, Math.round(maxScrollYFloat));

                removeCallbacks(delayedWindowCheck);

                scroller.fling(startX, startY, (int) -velocityX, (int) -velocityY, minX, maxX, minY, maxY);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - paddingLeft;
                int line = Math.max(0, (int) (y / lineHeight));
                ensureLineInWindow(line, true);
                String ln = getLineFromWindowLocal(line - windowStartLine);
                if (ln == null || ln.isEmpty()) {
                    return onSingleTapUp(e);
                }
                int charIndex = getCharIndexForX(ln, x);
                int[] bounds = computeWordBounds(ln, charIndex);
                selStartLine = selEndLine = line;
                selStartChar = bounds[0];
                selEndChar = bounds[1];
                hasSelection = true;
                isSelectAllActive = false;
                selecting = true;
                cursorLine = line;
                cursorChar = selEndChar;
                showPopupAtSelection();
                invalidate();

                // Show keyboard and automatically scroll to ensure cursor is visible
                showKeyboard();
                return true;
            }
        });

        lineWidthCache = new LinkedHashMap<Integer, Float>(LINE_WIDTH_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Float> eldest) {
                return size() > LINE_WIDTH_CACHE_SIZE;
            }
        };

        ioThread = new HandlerThread("PopEditIO");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());

        setFocusable(true);
        setFocusableInTouchMode(true);

        getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            getWindowVisibleDisplayFrame(visibleDisplayFrame);
            int rootViewHeight = getRootView().getHeight();
            int heightDifference = rootViewHeight - visibleDisplayFrame.bottom;
            int newKeyboardHeight = (heightDifference > rootViewHeight * 0.15) ? heightDifference : 0;

            // Only process if keyboard height actually changed
            if (newKeyboardHeight != keyboardHeight) {
                int previousKeyboardHeight = keyboardHeight;
                keyboardHeight = newKeyboardHeight;

                if (newKeyboardHeight > 0 && isFocused()) {
                    // If keyboard just appeared and we have focus, ensure cursor is visible above it
                    post(() -> ensureCursorVisibleAfterKeyboard());
                } else if (previousKeyboardHeight > 0 && newKeyboardHeight == 0) {
                    // Keyboard just disappeared, restore to normal scrolling behavior
                    post(() -> {
                        // When keyboard disappears, ensure the cursor remains visible with normal logic
                        keepCursorVisibleHorizontally();
                    });
                }
            }
        });
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (draggingHandle == 0) return;
            if (autoScrollX != 0 || autoScrollY != 0) {
                scrollX += autoScrollX;
                scrollY += autoScrollY;
                clampScrollX();
                clampScrollY();
                updateHandlePosition(lastTouchX, lastTouchY);
                if (draggingHandle == 1 || draggingHandle == 2) {
                    showPopupAtSelection();
                }
                checkAndLoadWindow();
                invalidate();
                mainHandler.postDelayed(this, 16);
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(paddingLeft - scrollX, -scrollY);

        int firstVisibleLine = (int) (scrollY / lineHeight);
        int lastVisibleLine = firstVisibleLine + (int) Math.ceil(getHeight() / lineHeight) + 5;

        // Ensure we have a reasonable range to prevent excessive rendering
        if (firstVisibleLine < 0) firstVisibleLine = 0;
        if (lastVisibleLine > firstVisibleLine + 2000) lastVisibleLine = firstVisibleLine + 2000; // Limit rendering to prevent performance issues

        int maxLineIndex = windowStartLine + linesWindow.size() - 1;
        if (isEof) {
            maxLineIndex = windowStartLine + linesWindow.size() - 1;
        }

        for (int globalLine = firstVisibleLine; globalLine <= lastVisibleLine; globalLine++) {
            if (globalLine < 0) continue;

            if (globalLine > maxLineIndex && isEof && !linesWindow.isEmpty()) {
                float y = (globalLine + 1) * lineHeight - paint.descent();
                canvas.drawText("", 0, y, paint);
                continue;
            }

            String line = getLineFromWindowLocal(globalLine - windowStartLine);

            // If line is not in current window and we're not at EOF, attempt to load it if it's close
            if (line == null && !isEof) {
                // Only attempt to load lines that are close to the current window to prevent excessive loading
                if (Math.abs(globalLine - windowStartLine) < windowSize + prefetchLines) {
                    ensureLineInWindow(globalLine, false);
                    line = getLineFromWindowLocal(globalLine - windowStartLine);
                }

                // Draw an empty line as a placeholder until the content is loaded
                float y = (globalLine + 1) * lineHeight - paint.descent();
                canvas.drawText("", 0, y, paint);
                continue;
            }

            boolean lineExists = line != null;

            if (hasSelection) {
                Paint selPaint = new Paint();
                selPaint.setColor(0x8033B5E5);
                float top = globalLine * lineHeight;
                float bottom = top + lineHeight;

                if (isSelectAllActive) {
                    if (lineExists || !isEof) {
                        float right = getWidth() + scrollX;
                        if (isEof && globalLine == (windowStartLine + linesWindow.size() - 1)) {
                            right = paint.measureText(line);
                        }
                        canvas.drawRect(0, top, right, bottom, selPaint);
                    }
                } else {
                    int startLine, endLine, startChar, endChar;
                    if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) <= 0) {
                        startLine = selStartLine; startChar = selStartChar;
                        endLine = selEndLine; endChar = selEndChar;
                    } else {
                        startLine = selEndLine; startChar = selEndChar;
                        endLine = selStartLine; endChar = selStartChar;
                    }

                    if (globalLine >= startLine && globalLine <= endLine) {
                        // Get the actual line text for selection calculation, even if it's not in current window
                        String currentLine = null;
                        boolean lineInWindow = (globalLine >= windowStartLine && globalLine < windowStartLine + linesWindow.size());

                        if (lineInWindow) {
                            currentLine = getLineFromWindowLocal(globalLine - windowStartLine);
                        } else if (!isEof) {
                            // Try to load the line if it's not in the current window but not at EOF
                            // Only load if it's within a reasonable distance to avoid excessive loading
                            if (Math.abs(globalLine - windowStartLine) < windowSize + prefetchLines) {
                                ensureLineInWindow(globalLine, false);
                                currentLine = getLineFromWindowLocal(globalLine - windowStartLine);
                            }
                        }

                        if (currentLine == null) currentLine = "";

                        float left, right;
                        if (startLine == endLine) {
                            left = paint.measureText(currentLine, 0, Math.max(0, Math.min(startChar, currentLine.length())));
                            right = paint.measureText(currentLine, 0, Math.max(0, Math.min(endChar, currentLine.length())));
                        } else {
                            if (globalLine == startLine) {
                                left = paint.measureText(currentLine, 0, Math.max(0, Math.min(startChar, currentLine.length())));
                                right = getWidth() + scrollX;
                            } else if (globalLine == endLine) {
                                left = 0;
                                right = paint.measureText(currentLine, 0, Math.max(0, Math.min(endChar, currentLine.length())));
                            } else {
                                left = 0;
                                right = getWidth() + scrollX;
                            }
                        }
                        if (right > left) {
                            canvas.drawRect(left, top, right, bottom, selPaint);
                        }
                    }
                }
            }
            if (line == null) line = "";
            float y = (globalLine + 1) * lineHeight - paint.descent();
            canvas.drawText(line, 0, y, paint);
        }

        if (isFocused() && !hasSelection) {
            // Handle cursor drawing even when it's outside the loaded window
            String cursorLineText = null;
            boolean cursorInWindow = (cursorLine >= windowStartLine && cursorLine < windowStartLine + linesWindow.size());

            if (cursorInWindow) {
                cursorLineText = getLineFromWindowLocal(cursorLine - windowStartLine);
            } else if (!isEof) {
                // If cursor is outside window but not at EOF, try to load the line
                ensureLineInWindow(cursorLine, false);
            }

            if (cursorLineText == null) cursorLineText = "";
            int safeChar = Math.min(cursorChar, cursorLineText.length());
            float cursorX = paint.measureText(cursorLineText.substring(0, safeChar));
            float cursorY = cursorLine * lineHeight;
            Paint caretPaint = new Paint();
            caretPaint.setColor(0xFF2196F3);
            caretPaint.setStrokeWidth(3f);
            canvas.drawLine(cursorX, cursorY, cursorX, cursorY + lineHeight, caretPaint);
            Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handlePaint.setColor(0xFF2196F3);
            float handleX = cursorX;
            float handleY = cursorY + lineHeight;

            // Adjust handle position if we are at or near the bottom of the document
            if (isEof && cursorLine >= windowStartLine + linesWindow.size() - 1) {
                // Keep the cursor handle above the bottom with the same offset as scroll padding
                float viewBottom = getHeight();
                float effectiveBottom = (keyboardHeight > 0) ? getHeight() - keyboardHeight : viewBottom - BOTTOM_SCROLL_OFFSET;
                float currentHandleY = cursorY + lineHeight - scrollY;

                // If handle is too close to the bottom, adjust it upward
                if (currentHandleY > effectiveBottom) {
                    handleY = (scrollY + effectiveBottom) + lineHeight;
                }
            } else if (keyboardHeight > 0) {
                // When keyboard is visible, ensure the cursor handle stays above it with good padding
                float keyboardTop = getHeight() - keyboardHeight;
                float currentHandleY = cursorY + lineHeight - scrollY;

                if (currentHandleY >= keyboardTop) {
                    float paddingAboveKeyboard = Math.min(lineHeight * 1.5f, keyboardHeight * 0.2f); // More substantial padding
                    handleY = scrollY + keyboardTop - paddingAboveKeyboard;
                }
            }

            drawTeardropHandle(canvas, handleX, handleY, handlePaint);
            cursorHandleRect.set(handleX - handleRadius, handleY, handleX + handleRadius, handleY + handleRadius * 2);
        }

        if (hasSelection) {
            Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handlePaint.setColor(0xFF2196F3);

            // Handle start selection handle drawing even when it's outside the loaded window
            boolean startInWindow = (selStartLine >= windowStartLine && selStartLine < windowStartLine + linesWindow.size());
            String startLineText = null;

            if (startInWindow) {
                startLineText = getLineFromWindowLocal(selStartLine - windowStartLine);
            } else if (!isEof) {
                // Try to load the line if selection is outside window but not at EOF
                ensureLineInWindow(selStartLine, false);
            }

            if (startLineText == null) startLineText = "";
            float startX = paint.measureText(startLineText, 0, Math.max(0, Math.min(selStartChar, startLineText.length())));
            float startY = selStartLine * lineHeight + lineHeight;

            // Adjust handle position if we are at or near the bottom of the document
            float adjustedStartY = startY;
            if (isEof && selStartLine >= windowStartLine + linesWindow.size() - 1) {
                // Keep the selection handle above the bottom with the same offset as scroll padding
                float viewBottom = getHeight();
                float effectiveBottom = (keyboardHeight > 0) ? getHeight() - keyboardHeight : viewBottom - BOTTOM_SCROLL_OFFSET;
                float currentHandleY = startY - scrollY;

                // If handle is too close to the bottom, adjust it upward
                if (currentHandleY > effectiveBottom) {
                    adjustedStartY = scrollY + effectiveBottom;
                }
            } else if (keyboardHeight > 0) {
                // When keyboard is visible, ensure the selection handle stays above it with good padding
                float keyboardTop = getHeight() - keyboardHeight;
                float currentHandleY = startY - scrollY;

                if (currentHandleY >= keyboardTop) {
                    float paddingAboveKeyboard = Math.min(lineHeight * 1.5f, keyboardHeight * 0.2f); // More substantial padding
                    adjustedStartY = scrollY + keyboardTop - paddingAboveKeyboard;
                }
            }

            drawTeardropHandle(canvas, startX, adjustedStartY, handlePaint);
            leftHandleRect.set(startX - handleRadius, adjustedStartY, startX + handleRadius, adjustedStartY + handleRadius * 2);

            float endX, endY;
            if (isSelectAllActive) {
                int lastActualLineInWindow = windowStartLine + linesWindow.size() - 1;
                int visualEndLine = Math.min(lastVisibleLine, lastActualLineInWindow);
                if (visualEndLine < 0) visualEndLine = 0;

                String endLineText = getLineFromWindowLocal(visualEndLine - windowStartLine);
                if (endLineText == null) endLineText = "";
                endX = paint.measureText(endLineText);
                endY = visualEndLine * lineHeight + lineHeight;
            } else {
                // Handle end selection handle drawing even when it's outside the loaded window
                boolean endInWindow = (selEndLine >= windowStartLine && selEndLine < windowStartLine + linesWindow.size());
                String endLineText = null;

                if (endInWindow) {
                    endLineText = getLineFromWindowLocal(selEndLine - windowStartLine);
                } else if (!isEof) {
                    // Try to load the line if selection is outside window but not at EOF
                    ensureLineInWindow(selEndLine, false);
                }

                if (endLineText == null) endLineText = "";
                endX = paint.measureText(endLineText, 0, Math.max(0, Math.min(selEndChar, endLineText.length())));
                endY = selEndLine * lineHeight + lineHeight;
            }

            if (selEndLine < windowStartLine + linesWindow.size() || isSelectAllActive) {
                // Adjust handle position if we are at or near the bottom of the document
                float adjustedEndY = endY;
                if (isEof && selEndLine >= windowStartLine + linesWindow.size() - 1) {
                    // Keep the selection handle above the bottom with the same offset as scroll padding
                    float viewBottom = getHeight();
                    float effectiveBottom = (keyboardHeight > 0) ? getHeight() - keyboardHeight : viewBottom - BOTTOM_SCROLL_OFFSET;
                    float currentHandleY = endY - scrollY;

                    // If handle is too close to the bottom, adjust it upward
                    if (currentHandleY > effectiveBottom) {
                        adjustedEndY = scrollY + effectiveBottom;
                    }
                } else if (keyboardHeight > 0) {
                    // When keyboard is visible, ensure the selection handle stays above it with good padding
                    float keyboardTop = getHeight() - keyboardHeight;
                    float currentHandleY = endY - scrollY;

                    if (currentHandleY >= keyboardTop) {
                        float paddingAboveKeyboard = Math.min(lineHeight * 1.5f, keyboardHeight * 0.2f); // More substantial padding
                        adjustedEndY = scrollY + keyboardTop - paddingAboveKeyboard;
                    }
                }

                drawTeardropHandle(canvas, endX, adjustedEndY, handlePaint);
                rightHandleRect.set(endX - handleRadius, adjustedEndY, endX + handleRadius, adjustedEndY + handleRadius * 2);
            }
        }
        canvas.restore();

        if (showPopup) {
            drawPopup(canvas);
        }

        if (showLoadingCircle) {
            Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setColor(loadingCircleColor);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(8f);
            circlePaint.setStrokeCap(Paint.Cap.ROUND);

            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;

            canvas.save();
            canvas.rotate(loadingCircleRotation, centerX, centerY);

            RectF rect = new RectF(centerX - loadingCircleRadius, centerY - loadingCircleRadius,
                    centerX + loadingCircleRadius, centerY + loadingCircleRadius);
            circlePaint.setColor(loadingCircleColor);
            canvas.drawArc(rect, 0, 270, false, circlePaint);

            canvas.restore();
        }
    }

    private void drawTeardropHandle(Canvas canvas, float cx, float cy, Paint paint) {
        Path path = new Path();
        float totalHeight = handleRadius * 2f;
        float bulbRadius = handleRadius;
        path.moveTo(cx, cy);
        path.cubicTo(cx - bulbRadius, cy + totalHeight * 0.5f, cx - bulbRadius, cy + totalHeight * 0.7f, cx, cy + totalHeight);
        path.cubicTo(cx + bulbRadius, cy + totalHeight * 0.7f, cx + bulbRadius, cy + totalHeight * 0.5f, cx, cy);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawPopup(Canvas canvas) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFFFFFFFF);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(0xFFCCCCCC);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x33000000);
        RectF shadowRect = new RectF(popupRect.left + 2, popupRect.top + 4, popupRect.right + 2, popupRect.bottom + 4);
        canvas.drawRoundRect(shadowRect, popupCorner, popupCorner, shadow);
        canvas.drawRoundRect(popupRect, popupCorner, popupCorner, bgPaint);
        canvas.drawRoundRect(popupRect, popupCorner, popupCorner, border);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTextSize(30f);
        txt.setColor(0xFF000000);
        drawButton(canvas, btnCopyRect, "Copy", txt);
        drawButton(canvas, btnCutRect, "Cut", txt);
        drawButton(canvas, btnPasteRect, "Paste", txt);
        drawButton(canvas, btnSelectAllRect, "Select All", txt);
    }

    private void drawButton(Canvas canvas, RectF r, String label, Paint txtPaint) {
        Paint btnBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnBg.setColor(0xFFF5F5F5);
        canvas.drawRoundRect(r, 12f, 12f, btnBg);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(0xFFCCCCCC);
        canvas.drawRoundRect(r, 12f, 12f, stroke);
        float textWidth = txtPaint.measureText(label);
        float cx = r.left + (r.width() / 2f);
        float cy = r.top + (r.height() / 2f) - ((txtPaint.descent() + txtPaint.ascent()) / 2f);
        canvas.drawText(label, cx - textWidth / 2f, cy, txtPaint);
    }

    private void showPopupAtSelection() {
        if (!hasSelection) return;
        int visualEndLine, visualEndChar;
        if (isSelectAllActive) {
            if (selEndLine >= windowStartLine && selEndLine < windowStartLine + linesWindow.size()) {
                visualEndLine = selEndLine;
                String line = getLineFromWindowLocal(selEndLine - windowStartLine);
                if (line == null) line = "";
                visualEndChar = selEndChar;
            } else {
                visualEndLine = Math.max(0, windowStartLine + linesWindow.size() - 1);
                String line = getLineFromWindowLocal(visualEndLine - windowStartLine);
                if (line == null) line = "";
                visualEndChar = Math.max(0, line.length() / 2);
            }
        } else {
            visualEndLine = selEndLine;
            visualEndChar = selEndChar;
        }
        String ln = getLineFromWindowLocal(visualEndLine - windowStartLine);
        if (ln == null) ln = "";
        float selX = paint.measureText(ln, 0, Math.max(0, Math.min(visualEndChar, ln.length())));
        float selY = (visualEndLine * lineHeight);
        float viewX = paddingLeft + selX - scrollX;
        float viewY = selY - scrollY;
        float totalWidth = btnWidth * 5 + btnSpacing * 4 + popupPadding * 2;
        float totalHeight = btnHeight + popupPadding * 2;
        float left = viewX - (totalWidth / 2f);

        // Adjust popup positioning to account for bottom padding if needed
        float top = viewY - totalHeight - 10f;
        if (left < 8f) left = 8f;
        if (left + totalWidth > getWidth() - 8f) left = getWidth() - 8f - totalWidth;

        // Adjust top if it would go off-screen or too close to bottom with padding
        float viewBottom = getHeight();
        if (top < 8f) {
            if (viewY + lineHeight + 10f > viewBottom - BOTTOM_SCROLL_OFFSET) {
                // If popup would go below safe area, position it above the selection
                top = viewY - totalHeight - 10f;
            } else {
                top = viewY + lineHeight + 10f;
            }
        } else if (top + totalHeight > viewBottom - BOTTOM_SCROLL_OFFSET) {
            // If popup would extend into bottom padding area, position above
            top = viewY - totalHeight - 10f;
        }

        popupRect.set(left, top, left + totalWidth, top + totalHeight);
        float bx = popupRect.left + popupPadding;
        float by = popupRect.top + popupPadding;
        btnCopyRect.set(bx, by, bx + btnWidth, by + btnHeight);
        btnCutRect.set(btnCopyRect.right + btnSpacing, by, btnCopyRect.right + btnSpacing + btnWidth, by + btnHeight);
        btnPasteRect.set(btnCutRect.right + btnSpacing, by, btnCutRect.right + btnSpacing + btnWidth, by + btnHeight);
        btnSelectAllRect.set(btnPasteRect.right + btnSpacing, by, btnPasteRect.right + btnSpacing + btnWidth, by + btnHeight);
        showPopup = true;
        invalidate();
    }

    private void hidePopup() {
        showPopup = false;
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollerIsScrolling = true;
            scrollY = scroller.getCurrY();
            scrollX = scroller.getCurrX();
            clampScrollY();
            clampScrollX();

            // Periodically check for window loading during a fling
            removeCallbacks(delayedWindowCheck);
            postDelayed(delayedWindowCheck, 50);

            postInvalidateOnAnimation();
        } else {
            if (scrollerIsScrolling) {
                scrollerIsScrolling = false;
                // Final check once fling is complete
                checkAndLoadWindow();
                if (hasSelection) {
                    showPopupAtSelection();
                }
            }
        }
    }

    private void clampScrollY() {
        // منع الصعود للأعلى إذا كنا نقوم بتحميل نافذة سابقة
        if (isWindowLoading && scrollY < windowStartLine * lineHeight) {
            scrollY = windowStartLine * lineHeight;
            if (!scroller.isFinished()) {
                scroller.abortAnimation();
            }
        }

        if (scrollY < 0) scrollY = 0;

        float maxScroll;
        float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();

        if (isEof) {
            // إذا وصلنا لنهاية الملف الحقيقية
            // When keyboard is visible, ensure the last line stays above the keyboard with padding
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
            maxScroll = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight - (effectiveHeight - paddingToUse));
        } else {
            // تعديل جوهري هنا (FIX):
            // لا نلزم السكرول بالتوقف عند آخر سطر محمل إذا كان هناك المزيد في الملف.
            // نضيف "هامش افتراضي" (Buffer) يعادل نصف النافذة تقريباً للسماح للمستخدم بالسحب
            // ريثما يتم تحميل البيانات في الخلفية.
            float virtualExtraSpace = Math.max(prefetchLines * lineHeight, 2000f); // Larger buffer for better experience
            maxScroll = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight + virtualExtraSpace - effectiveHeight);

            // When keyboard is visible, ensure the scroll can reach the virtual space properly
            if (keyboardHeight > 0) {
                maxScroll = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight + virtualExtraSpace - effectiveHeight);
            }
        }

        if (scrollY > maxScroll) {
            scrollY = maxScroll;
            // This is the fix: Only abort the animation if we have truly reached the end of the file (isEof).
            // If we are not at the end, we let the scroller continue into the "virtual" space.
            // This creates a smooth scrolling experience while new content loads in the background,
            // preventing the stuttering/flickering issue when scrolling fast in large files.
            // Also, to handle keyboard changes more smoothly, don't abort if keyboard height changed recently
            if (isEof && !scroller.isFinished()) {
                scroller.abortAnimation();
            }
        }
    }

    private void checkAndLoadWindow() {
        if (sourceFile == null) return;
        if (getWidth() == 0 || getHeight() == 0) return;

        int firstVisibleLine = (int) (scrollY / lineHeight);
        int lastVisibleLine = firstVisibleLine + (int) Math.ceil(getHeight() / lineHeight);
        int buffer = prefetchLines / 2;

        firstVisibleLine = Math.max(0, firstVisibleLine);
        lastVisibleLine = Math.max(firstVisibleLine, lastVisibleLine);

        // Expand the buffer to trigger loading more proactively
        int expandedBuffer = Math.max(buffer, (int) Math.ceil(getHeight() / lineHeight) + 100); // More aggressive loading for large files

        // Check if we need to load more content in the forward direction
        if (!isEof && lastVisibleLine >= windowStartLine + linesWindow.size() - expandedBuffer) {
            // Calculate how far ahead we should load to prevent stuttering
            int targetStart = Math.max(0, firstVisibleLine - prefetchLines / 2);
            if (targetStart > windowStartLine) {
                loadWindowAround(targetStart, null);
            }
        }
        // Check if we need to load more content in the backward direction
        else if (firstVisibleLine < windowStartLine + buffer) {
            int targetStart = Math.max(0, firstVisibleLine - prefetchLines);
            if (targetStart < windowStartLine) {
                loadWindowAround(targetStart, null);
            }
        }
        // Additional check: if we're far from the loaded window (e.g., jumped to a distant line), load it
        else if (Math.abs(firstVisibleLine - windowStartLine) > windowSize) {
            int targetStart = Math.max(0, firstVisibleLine - windowSize / 4);
            loadWindowAround(targetStart, null);
        }
    }

    private void loadWindowAround(int startLine, @Nullable Runnable onComplete) {
        if (isWindowLoading) return;

        if (isFileCleared) {
            post(() -> {
                synchronized (linesWindow) {
                    linesWindow.clear();
                    linesWindow.add("");
                    windowStartLine = 0;
                    isEof = true;
                }
                if (onComplete != null) {
                    onComplete.run();
                }
                invalidate();
                checkAndLoadWindow();
            });
            return;
        }
        if (sourceFile == null) {
            if (onComplete != null) {
                post(onComplete);
            }
            return;
        }

        isWindowLoading = true;
        final int taskVersion = ioTaskVersion.incrementAndGet();
        final int s = Math.max(0, Math.min(startLine, Integer.MAX_VALUE / 2));

        Runnable task = () -> {
            try {
                if (taskVersion != ioTaskVersion.get()) {
                    post(() -> {
                        isWindowLoading = false;
                        checkAndLoadWindow();
                    });
                    return;
                }

                List<String> newWin = new ArrayList<>();
                BufferedReader br;

                if (sourceFile != null && isIndexReady) {
                    RandomAccessFile raf = new RandomAccessFile(sourceFile, "r");
                    synchronized (lineOffsets) {
                        if (startLine >= 0 && startLine < lineOffsets.size()) {
                            raf.seek(lineOffsets.get(startLine));
                        } else {
                            raf.seek(raf.length());
                        }
                    }
                    br = new BufferedReader(Channels.newReader(raf.getChannel(), "UTF-8"));
                } else {
                    br = reopenReaderAtStart();
                    if (br == null) {
                        if (onComplete != null) post(onComplete);
                        post(() -> isWindowLoading = false);
                        return;
                    }
                    long linesToSkip = s;
                    long skipped = 0;
                    while (skipped < linesToSkip && (br.readLine()) != null) {
                        skipped++;
                    }
                }

                int linesRead = 0;
                String ln;
                while (linesRead < windowSize + prefetchLines && (ln = br.readLine()) != null) {
                    newWin.add(ln);
                    linesRead++;
                }
                boolean eof = linesRead < windowSize + prefetchLines;

                synchronized (modifiedLines) {
                    for (int i = 0; i < newWin.size(); i++) {
                        int globalLineNum = s + i;
                        if (modifiedLines.containsKey(globalLineNum)) {
                            newWin.set(i, modifiedLines.get(globalLineNum));
                        }
                    }
                }

                int currentLineIndex = s;
                for (String line : newWin) {
                    computeWidthForLine(currentLineIndex, line);
                    currentLineIndex++;
                }

                if (taskVersion != ioTaskVersion.get()) {
                    post(() -> {
                        isWindowLoading = false;
                        checkAndLoadWindow();
                    });
                    return;
                }

                post(() -> {
                    isWindowLoading = false;

                    if (taskVersion != ioTaskVersion.get()) {
                        checkAndLoadWindow();
                        return;
                    }
                    synchronized (linesWindow) {
                        linesWindow.clear();
                        linesWindow.addAll(newWin);
                        windowStartLine = s;
                        isEof = eof;
                    }
                    invalidate();

                    if (onComplete != null) {
                        onComplete.run();
                    }

                    checkAndLoadWindow();
                });
            } catch (Exception e) {
                e.printStackTrace();
                post(() -> {
                    isWindowLoading = false;
                    if (onComplete != null) {
                        onComplete.run();
                    }
                    checkAndLoadWindow();
                });
            }
        };

        ioHandler.post(task);
    }

    private void buildFileIndex() {
        if (sourceFile == null || !sourceFile.exists()) {
            return;
        }
        final int taskVersion = ioTaskVersion.get();

        ioHandler.post(() -> {
            long[] offsets = buildIndexJava(sourceFile.getAbsolutePath());

            if (taskVersion != ioTaskVersion.get()) return;

            if (offsets != null) {
                List<Long> newOffsets = new ArrayList<>(offsets.length);
                for (long offset : offsets) {
                    newOffsets.add(offset);
                }

                synchronized (lineOffsets) {
                    if (taskVersion == ioTaskVersion.get()) {
                        lineOffsets.clear();
                        lineOffsets.addAll(newOffsets);
                        isIndexReady = true;
                    }
                }
            } else {
                synchronized (lineOffsets) {
                    isIndexReady = false;
                }
            }
        });
    }

    private void invalidatePendingIO() {
        ioTaskVersion.incrementAndGet();
        ioHandler.removeCallbacksAndMessages(null);
    }

    public void loadFromFile(final File file) {
        invalidatePendingIO();
        isFileCleared = false;
        isSelectAllActive = false;
        sourceFile = file;
        windowStartLine = 0;
        linesWindow.clear();
        modifiedLines.clear();
        lineWidthCache.clear();
        synchronized (lineOffsets) {
            lineOffsets.clear();
        }
        isIndexReady = false;
        cursorLine = 0;
        cursorChar = 0;
        isEof = false;
        scrollY = 0;
        scrollX = 0;
        loadWindowAround(0, null);
        ioHandler.post(this::buildFileIndex);
    }

    public void setWindowSize(int size) {
        windowSize = Math.max(10, size);
    }

    public void setPrefetchLines(int p) {
        prefetchLines = Math.max(0, p);
    }

    public void setTextColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setDisable(boolean disable) {
        this.isDisabled = disable;
        if (disable) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        }
    }

    public void showLoadingCircle(boolean show) {
        showLoadingCircle = show;

        if (show) {
            if (rotationAnimator == null) {
                rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
                rotationAnimator.setDuration(1000);
                rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
                rotationAnimator.addUpdateListener(animation -> {
                    loadingCircleRotation = (float) animation.getAnimatedValue();
                    invalidate();
                });
            }
            if (!rotationAnimator.isRunning()) {
                rotationAnimator.start();
            }
        } else {
            if (rotationAnimator != null && rotationAnimator.isRunning()) {
                rotationAnimator.cancel();
            }
            loadingCircleRotation = 0f;
        }
        invalidate();
    }

    public void goToLine(int line) {
        goToLine(line, 1);
    }

    public void goToLine(int line, int col) {
        final int currentGoToLineVersion = goToLineVersion.incrementAndGet();
        setDisable(true);
        showLoadingCircle(true);

        if (hasSelection) {
            hasSelection = false;
            isSelectAllActive = false;
            selecting = false;
            hidePopup();
        }

        final int targetLine = Math.max(0, line - 1);
        final int targetCol = Math.max(0, col - 1);

        Runnable completionAction = () -> {
            if (currentGoToLineVersion != goToLineVersion.get()) {
                return;
            }

            cursorLine = targetLine;

            if (isFileCleared) {
                cursorLine = 0;
                cursorChar = 0;
            } else if (cursorLine >= windowStartLine && cursorLine < windowStartLine + linesWindow.size()) {
                String lineText = getLineFromWindowLocal(cursorLine - windowStartLine);
                if (lineText == null) lineText = "";
                cursorChar = Math.max(0, Math.min(targetCol, lineText.length()));
            } else if (isEof) {
                int lastLineInDoc = windowStartLine + linesWindow.size() - 1;
                if (cursorLine > lastLineInDoc) {
                    cursorLine = Math.max(0, lastLineInDoc);
                    String lineText = getLineFromWindowLocal(cursorLine - windowStartLine);
                    if (lineText == null) lineText = "";
                    cursorChar = Math.max(0, Math.min(targetCol, lineText.length()));
                }
            } else {
                cursorChar = 0;
            }
            keepCursorVisibleHorizontally();

            setDisable(false);
            showLoadingCircle(false);

            // Ensure focus is properly set and input connection is ready
            requestFocus();

            // Post to ensure we run after the view is fully updated
            post(() -> {
                showKeyboard();

                // Also request focus again to ensure the view has focus after being re-enabled
                requestFocus();

                // Restart input to ensure the IME is properly connected after re-enabling
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.restartInput(this);
                }
            });
        };

        if (isFileCleared) {
            completionAction.run();
            setDisable(false);
            showLoadingCircle(false);
        } else if (targetLine >= windowStartLine && targetLine < windowStartLine + linesWindow.size()) {
            completionAction.run();
            setDisable(false);
            showLoadingCircle(false);
        } else {
            int targetStart = Math.max(0, targetLine - prefetchLines);
            loadWindowAround(targetStart, completionAction);
        }
    }

    public void insertCharAtCursor(char c) {
        invalidatePendingIO();
        if (isFileCleared) {
            isFileCleared = false;
        }
        if (hasSelection) {
            deleteSelection();
            if (hasComposing) {
                hasComposing = false;
                composingLength = 0;
            }
        }

        // Ensure the line is loaded in the window before inserting
        ensureLineInWindow(cursorLine, true);

        // Wait if the line is still loading and the cursor line is not in the current window
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            // Use a post to delay the insertion until the window is loaded
            post(() -> {
                // Retry the insertion after the window loads
                insertCharAtCursor(c);
            });
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        if (localIdx < 0 || localIdx >= linesWindow.size()) {
            if (linesWindow.isEmpty()) linesWindow.add("");
            localIdx = Math.max(0, Math.min(localIdx, linesWindow.size() - 1));
        }
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";
            if (c == '\n') {
                String before = base.substring(0, Math.min(cursorChar, base.length()));
                String after = base.substring(Math.min(cursorChar, base.length()));
                updateLocalLine(localIdx, before);
                linesWindow.add(localIdx + 1, after);
                modifiedLines.put(cursorLine, before);
                modifiedLines.put(cursorLine + 1, after);
                cursorLine++;
                cursorChar = 0;
            } else {
                int pos = Math.max(0, Math.min(cursorChar, base.length()));
                String modified = base.substring(0, pos) + c + base.substring(pos);
                updateLocalLine(localIdx, modified);
                modifiedLines.put(cursorLine, modified);
                cursorChar++;
                computeWidthForLine(cursorLine, modified);
            }
            invalidate();
            keepCursorVisibleHorizontally();
        }
    }

    public void insertNewlineAtCursor() {
        insertCharAtCursor('\n');
    }

    public void deleteCharAtCursor() {
        invalidatePendingIO();
        if (hasComposing) {
            deleteComposing();
            return;
        }
        if (isFileCleared) {
            if (cursorLine == 0 && cursorChar > 0) {
                cursorChar = Math.max(0, cursorChar - 1);
                invalidate();
            }
            return;
        }

        // Ensure the line is loaded in the window before deleting
        ensureLineInWindow(cursorLine, true);

        // Wait if the line is still loading and the cursor line is not in the current window
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            // Use a post to delay the deletion until the window is loaded
            post(() -> {
                // Retry the deletion after the window loads
                deleteCharAtCursor();
            });
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        if (localIdx < 0 || localIdx >= linesWindow.size()) return;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";
            if (cursorChar > 0) {
                int safeStart = Math.max(0, Math.min(cursorChar - 1, base.length()));
                int safeEnd = Math.max(safeStart, Math.min(cursorChar, base.length()));
                if (safeStart <= safeEnd && safeStart <= base.length() && safeEnd <= base.length() && base.length() > 0) {
                    String modified = base.substring(0, safeStart) + base.substring(safeEnd);
                    updateLocalLine(localIdx, modified);
                    modifiedLines.put(cursorLine, modified);
                    cursorChar = Math.max(0, cursorChar - 1);
                    computeWidthForLine(cursorLine, modified);
                    invalidateLineGlobal(cursorLine);
                } else if (base.length() == 0) {
                    cursorChar = Math.max(0, cursorChar - 1);
                    invalidateLineGlobal(cursorLine);
                }
            } else if (cursorLine > 0) {
                int prevGlobal = cursorLine - 1;
                ensureLineInWindow(prevGlobal, true);
                int prevLocal = prevGlobal - windowStartLine;
                if (prevLocal < 0 || prevLocal >= linesWindow.size()) return;
                String prev = getLineFromWindowLocal(prevLocal);
                if (prev == null) prev = "";
                String merged = prev + base;
                updateLocalLine(prevLocal, merged);
                modifiedLines.put(prevGlobal, merged);
                if (localIdx < linesWindow.size()) linesWindow.remove(localIdx);
                cursorLine = prevGlobal;
                cursorChar = prev.length();
                computeWidthForLine(prevGlobal, merged);
                invalidate();
            }
        }
    }

    public void deleteForwardAtCursor() {
        invalidatePendingIO();
        if (hasComposing) {
            deleteComposing();
            return;
        }
        if (isFileCleared) return;

        // Ensure the line is loaded in the window before deleting
        ensureLineInWindow(cursorLine, true);

        // Wait if the line is still loading and the cursor line is not in the current window
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            // Use a post to delay the deletion until the window is loaded
            post(() -> {
                // Retry the deletion after the window loads
                deleteForwardAtCursor();
            });
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";
            if (cursorChar < base.length()) {
                int safeStart = Math.max(0, Math.min(cursorChar, base.length()));
                int safeEnd = Math.max(safeStart, Math.min(cursorChar + 1, base.length()));
                if (safeStart <= safeEnd && safeStart <= base.length() && safeEnd <= base.length()) {
                    String modified = base.substring(0, safeStart) + base.substring(safeEnd);
                    updateLocalLine(localIdx, modified);
                    modifiedLines.put(cursorLine, modified);
                    computeWidthForLine(cursorLine, modified);
                    invalidateLineGlobal(cursorLine);
                }
            } else {
                int nextGlobal = cursorLine + 1;
                ensureLineInWindow(nextGlobal, true);
                int nextLocal = nextGlobal - windowStartLine;
                if (nextLocal >= 0 && nextLocal < linesWindow.size()) {
                    String next = getLineFromWindowLocal(nextLocal);
                    if (next == null) next = "";
                    String merged = base + next;
                    updateLocalLine(localIdx, merged);
                    linesWindow.remove(nextLocal);
                    modifiedLines.put(cursorLine, merged);
                    computeWidthForLine(cursorLine, merged);
                    invalidate();
                }
            }
        }
    }

    private void commitComposing(boolean keepInText) {
        if (!hasComposing) return;
        hasComposing = false;
        composingLength = 0;
        invalidate();
    }

    private void replaceComposingWith(CharSequence textSeq) {
        invalidatePendingIO();
        ensureLineInWindow(composingLine, true);

        // Wait if the line is still loading and the composing line is not in the current window
        if (isWindowLoading && (composingLine < windowStartLine || composingLine >= windowStartLine + linesWindow.size())) {
            // Use a post to delay the replacement until the window is loaded
            post(() -> {
                // Retry the replacement after the window loads
                replaceComposingWith(textSeq);
            });
            return;
        }

        int local = composingLine - windowStartLine;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(local);
            if (base == null) base = "";
            int start = Math.max(0, Math.min(composingOffset, base.length()));
            int end = Math.max(0, Math.min(composingOffset + composingLength, base.length()));
            StringBuilder sb = new StringBuilder();
            sb.append(base.substring(0, start));
            if (textSeq != null) sb.append(textSeq);
            sb.append(base.substring(end));
            String newLine = sb.toString();
            updateLocalLine(local, newLine);
            modifiedLines.put(composingLine, newLine);
            composingLength = (textSeq == null) ? 0 : textSeq.length();
            cursorLine = composingLine;
            cursorChar = composingOffset + composingLength;
            computeWidthForLine(composingLine, newLine);
            invalidate();
        }
    }

    private void deleteComposing() {
        if (!hasComposing) return;
        replaceComposingWith("");
        hasComposing = false;
        composingLength = 0;
    }

    private int comparePos(int lineA, int charA, int lineB, int charB) {
        if (lineA != lineB) return Integer.compare(lineA, lineB);
        return Integer.compare(charA, charB);
    }

    public String getSelectedText() {
        if (!hasSelection) return null;
        if (isSelectAllActive) {
            final int MAX_LINES_TO_COPY = 25000;
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = null;
            try {
                reader = reopenReaderAtStart();
                if (reader == null) return "";
                String line;
                int linesRead = 0;
                while ((line = reader.readLine()) != null && linesRead < MAX_LINES_TO_COPY) {
                    sb.append(line).append('\n');
                    linesRead++;
                }
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            } catch (Exception e) {
                e.printStackTrace();
                return "Error reading file for copy.";
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        int sL = selStartLine, sC = selStartChar, eL = selEndLine, eC = selEndChar;
        if (comparePos(sL, sC, eL, eC) > 0) {
            int tL = sL, tC = sC;
            sL = eL;
            sC = eC;
            eL = tL;
            eC = tC;
        }
        StringBuilder sb = new StringBuilder();
        synchronized (linesWindow) {
            for (int L = sL; L <= eL; L++) {
                if (Math.abs(L - windowStartLine) < windowSize + prefetchLines) {
                    ensureLineInWindow(L, true);
                }
                String ln = getLineFromWindowLocal(L - windowStartLine);
                if (ln == null) ln = "";
                int startIdx = (L == sL) ? Math.max(0, Math.min(sC, ln.length())) : 0;
                int endIdx = (L == eL) ? Math.max(0, Math.min(eC, ln.length())) : ln.length();
                if (endIdx > startIdx) {
                    sb.append(ln.substring(startIdx, endIdx));
                }
                if (L < eL) sb.append('\n');
            }
        }
        return sb.toString();
    }

    public void copySelectionToClipboard() {
        String t = getSelectedText();
        if (t == null) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", t));
    }

    public void cutSelectionToClipboard() {
        if (!hasSelection) return;
        copySelectionToClipboard();
        deleteSelection();
    }

    public void pasteFromClipboard() {
        invalidatePendingIO();
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence txt = cd.getItemAt(0).coerceToText(getContext());
        if (txt == null) return;
        insertTextAtCursor(txt.toString());
    }

    interface LineCountCallback {
        void onResult(int count);
    }

    private void countTotalLines(LineCountCallback callback) {
    final int taskVersion = ioTaskVersion.get();

    ioHandler.post(() -> {
        if (taskVersion != ioTaskVersion.get()) {
            post(() -> callback.onResult(-1));
            return;
        }

        int count = 0;

        if (sourceFile != null && sourceFile.exists()) {
            try (InputStream is = new java.io.FileInputStream(sourceFile)) {

                byte[] buffer = new byte[8192];
                int len;
                boolean empty = true;

                while ((len = is.read(buffer)) != -1) {
                    empty = false;
                    for (int i = 0; i < len; i++) {
                        if (buffer[i] == '\n') {
                            count++;
                        }
                    }
                }

                // إذا الملف لم يكن فارغ، وكان آخر سطر لا ينتهي بـ \n
                if (!empty) {
                    count++; // = عدد الأسطر الحقيقي
                }

            } catch (Exception e) {
                e.printStackTrace();
                count = -1;
            }
        }

        final int finalCount = count;

        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> callback.onResult(finalCount));
    });
}

    public void selectAll() {
        // 1. Set UI state immediately
        setDisable(true);
        showLoadingCircle(true);
        isSelectAllActive = true;
        isEntireFileSelected = true;
        hasSelection = true;
        selStartLine = 0;
        selStartChar = 0;
        hidePopup();

        if (isFileCleared) {
            selEndLine = 0;
            selEndChar = 0;
            cursorLine = 0;
            cursorChar = 0;
            setDisable(false);
            showLoadingCircle(false);
            invalidate();
            showPopupAtSelection();
            return;
        }

        // 2. Callback to run once we know the line count
        final Runnable onLinesCounted = () -> {
            final int lastLineIndex = Math.max(0, selEndLine);
            // Load just the window needed for the end of the file
            int targetStart = Math.max(0, lastLineIndex - prefetchLines);

            loadWindowAround(targetStart, () -> {
                post(() -> {
                    // 4. Calculate end char and update cursor
                    int localIdx = lastLineIndex - windowStartLine;
                    String lastLineText = getLineFromWindowLocal(localIdx);
                    selEndChar = (lastLineText != null) ? lastLineText.length() : 0;

                    cursorLine = lastLineIndex;
                    cursorChar = selEndChar;

                    scrollY = Math.max(0, (lastLineIndex - 5) * lineHeight);
                    clampScrollY();

                    setDisable(false);
                    showLoadingCircle(false);
                    invalidate();

                    // Ensure focus is properly set and input connection is ready after re-enabling
                    requestFocus();
                    showPopupAtSelection();

                    // Post to ensure we run after the view is fully updated
                    post(() -> {
                        // Also request focus again to ensure the view has focus after being re-enabled
                        requestFocus();

                        // Restart input to ensure the IME is properly connected after re-enabling
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.restartInput(this);
                        }
                    });
                });
            });
        };

        // 3. Get the line count (Fastest method available)
        if (isIndexReady && !lineOffsets.isEmpty()) {
            // Instant access via index
            synchronized (lineOffsets) {
                selEndLine = Math.max(0, lineOffsets.size() - 1);
            }
            onLinesCounted.run();
        } else {
            // Optimized async count
            countTotalLines(totalLines -> {
                selEndLine = Math.max(0, totalLines - 1);
                onLinesCounted.run();
            });
        }
    }

    public void deleteSelection() {
        invalidatePendingIO();
        if (!hasSelection) return;

        if (isSelectAllActive) {
            synchronized (linesWindow) {
                linesWindow.clear();
                linesWindow.add("");
                windowStartLine = 0;
                modifiedLines.clear();
                modifiedLines.put(0, "");
                lineWidthCache.clear();
                isEof = true;
                cursorLine = 0;
                cursorChar = 0;
                hasSelection = false;
                isSelectAllActive = false;
                isEntireFileSelected = false;
                isFileCleared = true;
                selecting = false;
                selStartLine = selEndLine = 0;
                selStartChar = selEndChar = 0;
                hidePopup();
                synchronized (lineOffsets) {
                    lineOffsets.clear();
                }
                invalidate();
            }
            scrollY = 0;
            scrollX = 0;
            return;
        }

        int sL = selStartLine, sC = selStartChar, eL = selEndLine, eC = selEndChar;
        if (comparePos(sL, sC, eL, eC) > 0) {
            int tL = sL, tC = sC;
            sL = eL;
            sC = eC;
            eL = tL;
            eC = tC;
        }

        // Crash fix: If start of selection is not in the loaded window,
        // trigger a load and abort the current deletion to prevent a crash.
        // The user can press delete again after the content is loaded.
        if (sL < windowStartLine || sL >= windowStartLine + linesWindow.size()) {
            ensureLineInWindow(sL, true);
            return;
        }

        synchronized (linesWindow) {
            if (Math.abs(eL - windowStartLine) < windowSize + prefetchLines) {
                ensureLineInWindow(eL, true);
            }
            int eLocal = eL - windowStartLine;
            String last = getLineFromWindowLocal(eLocal);
            if (last == null) last = "";
            final String right = last.substring(Math.max(0, Math.min(eC, last.length())));

            if (Math.abs(sL - windowStartLine) < windowSize + prefetchLines) {
                ensureLineInWindow(sL, true);
            }
            int sLocal = sL - windowStartLine;
            String first = getLineFromWindowLocal(sLocal);
            if (first == null) first = "";
            final String left = first.substring(0, Math.max(0, Math.min(sC, first.length())));

            String merged = left + right;
            updateLocalLine(sLocal, merged);
            modifiedLines.put(sL, merged);

            int lastLineInWindow = windowStartLine + linesWindow.size() - 1;
            int endDeletionInWindow = Math.min(eL, lastLineInWindow);
            int numLinesToRemoveInWindow = endDeletionInWindow - sL;

            if (numLinesToRemoveInWindow > 0) {
                int startRemoveIndex = sLocal + 1;
                if (startRemoveIndex < linesWindow.size()) {
                    int endRemoveIndex = Math.min(startRemoveIndex + numLinesToRemoveInWindow, linesWindow.size());
                    if (startRemoveIndex < endRemoveIndex) {
                        linesWindow.subList(startRemoveIndex, endRemoveIndex).clear();
                    }
                }
            }

            cursorLine = sL;
            cursorChar = left.length();
            hasSelection = false;
            isSelectAllActive = false;
            selecting = false;
            selStartLine = selEndLine = cursorLine;
            selStartChar = selEndChar = cursorChar;
            hidePopup();
            computeWindowWidths();
            invalidate();
        }
    }

    private int getCharIndexForX(String text, float x) {
        if (text == null || text.length() == 0) return 0;
        float last = 0f;
        for (int i = 1; i <= text.length(); i++) {
            float w = paint.measureText(text, 0, i);
            if (w > x) {
                if (x < last + (w - last) / 2) return i - 1;
                return i;
            }
            last = w;
        }
        return text.length();
    }

    private int[] computeWordBounds(String line, int pos) {
        pos = Math.max(0, Math.min(pos, line.length()));
        if (line.length() == 0) return new int[]{0, 0};
        if (pos == line.length()) pos = Math.max(0, pos - 1);
        if (Character.isWhitespace(line.charAt(pos))) {
            int i = pos;
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
            if (i >= line.length()) {
                i = pos - 1;
                while (i >= 0 && Character.isWhitespace(line.charAt(i))) i--;
            }
            if (i < 0) return new int[]{pos, pos};
            pos = i;
        }
        int start = pos;
        int end = pos;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) start--;
        while (end < line.length() - 1 && !Character.isWhitespace(line.charAt(end + 1))) end++;
        return new int[]{start, end + 1};
    }

    private void insertTextAtCursor(String text) {
        invalidatePendingIO();
        if (text == null || text.isEmpty()) return;

        if (hasSelection) {
            deleteSelection();
            if (hasComposing) {
                hasComposing = false;
                composingLength = 0;
            }
        }

        String[] parts = text.split("\n", -1);

        // Ensure the line is loaded in the window before inserting
        ensureLineInWindow(cursorLine, true);

        // Wait if the line is still loading and the cursor line is not in the current window
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            // Use a post to delay the insertion until the window is loaded
            post(() -> {
                // Retry the insertion after the window loads
                insertTextAtCursor(text);
            });
            return;
        }

        int local = cursorLine - windowStartLine;
        if (local < 0 || local >= linesWindow.size()) {
            if (linesWindow.isEmpty()) {
                linesWindow.add("");
                local = 0;
            } else {
                local = Math.max(0, Math.min(local, linesWindow.size() - 1));
            }
        }

        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(local);
            if (base == null) base = "";
            int pos = Math.max(0, Math.min(cursorChar, base.length()));
            String left = base.substring(0, pos);
            String right = base.substring(pos);

            if (parts.length == 1) {
                String modified = left + parts[0] + right;
                updateLocalLine(local, modified);
                modifiedLines.put(cursorLine, modified);
                lineWidthCache.remove(cursorLine);
                cursorChar += parts[0].length();
            } else {
                lineWidthCache.clear();

                String firstLine = left + parts[0];
                updateLocalLine(local, firstLine);
                modifiedLines.put(cursorLine, firstLine);

                List<String> linesToInsert = new ArrayList<>();
                for (int p = 1; p < parts.length - 1; p++) {
                    linesToInsert.add(parts[p]);
                }
                String lastPart = parts[parts.length - 1];
                linesToInsert.add(lastPart + right);

                if (!linesToInsert.isEmpty()) {
                    linesWindow.addAll(local + 1, linesToInsert);
                }

                for (int i = 0; i < linesToInsert.size(); i++) {
                    modifiedLines.put(cursorLine + 1 + i, linesToInsert.get(i));
                }

                cursorLine += (parts.length - 1);
                cursorChar = lastPart.length();
            }

            keepCursorVisibleHorizontally();
        }
    }

    private void ensureLineInWindow(int globalLine, boolean blockingIfAbsent) {
        if (globalLine >= windowStartLine && globalLine < windowStartLine + linesWindow.size()) return;
        int targetStart = Math.max(0, globalLine - prefetchLines);
        loadWindowAround(targetStart, null);
    }

    private BufferedReader reopenReaderAtStart() {
        try {
            if (readerForFile != null) {
                try {
                    readerForFile.close();
                } catch (Exception ignored) {
                }
                readerForFile = null;
            }
            if (sourceFile != null) {
                readerForFile = new BufferedReader(new FileReader(sourceFile));
                return readerForFile;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void updateLocalLine(int localIdx, String text) {
        if (localIdx >= 0 && localIdx < linesWindow.size()) linesWindow.set(localIdx, text);
    }

    private String getLineFromWindowLocal(int localIdx) {
        if (localIdx < 0 || localIdx >= linesWindow.size()) return null;
        return linesWindow.get(localIdx);
    }

    private void computeWindowWidths() {
        synchronized (linesWindow) {
            int idx = windowStartLine;
            for (String s : linesWindow) {
                computeWidthForLine(idx, s);
                idx++;
            }
        }
    }

    private void computeWidthForLine(int globalIndex, String line) {
        float w = paint.measureText(line == null ? "" : line);
        synchronized (lineWidthCache) {
            lineWidthCache.put(globalIndex, w);
        }
    }

    private float getWidthForLine(int globalIndex, String line) {
        synchronized (lineWidthCache) {
            Float v = lineWidthCache.get(globalIndex);
            if (v != null) return v;
        }
        float w = paint.measureText(line == null ? "" : line);
        synchronized (lineWidthCache) {
            lineWidthCache.put(globalIndex, w);
        }
        return w;
    }

    private void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, 0);
        }
    }

    private void updateKeyboardHeight() {
        Rect r = new Rect();
        getWindowVisibleDisplayFrame(r);
        int screenHeight = getRootView().getHeight();
        int visibleHeight = r.bottom - r.top;
        int heightDiff = screenHeight - visibleHeight;

        if (heightDiff > screenHeight * 0.15) { // Threshold to detect keyboard
            keyboardHeight = heightDiff;
        } else {
            keyboardHeight = 0;
        }
    }

    private void ensureCursorVisibleAfterKeyboard() {
        // Calculate cursor position in view coordinates
        float cursorYTop = cursorLine * lineHeight;
        float cursorYBottom = cursorYTop + lineHeight;

        // Calculate the safe area above the keyboard
        float keyboardTop = getHeight() - keyboardHeight;
        float cursorViewY = cursorYBottom - scrollY; // Position of cursor in view coordinates

        if (keyboardHeight > 0) {
            // If keyboard is visible and cursor is below it or too close, scroll to make it visible
            if (cursorViewY >= keyboardTop - lineHeight) {
                // Calculate how much we need to scroll to position cursor above keyboard with padding
                float paddingAboveKeyboard = Math.min(lineHeight * 2f, keyboardHeight * 0.2f); // Substantial padding
                float targetScrollY = cursorYBottom - (getHeight() - keyboardHeight - paddingAboveKeyboard);

                // Apply the new scroll position and re-clamp
                scrollY = Math.max(0, targetScrollY);
                clampScrollY();
                invalidate();
            }
        } else {
            // When keyboard is hidden, use the normal visibility logic to ensure cursor is visible
            keepCursorVisibleHorizontally();
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (isDisabled) return null;
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, true) {
            @Override
            public Editable getEditable() {
                return imeEditable;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text == null) return super.commitText(text, newCursorPosition);

                // Ensure line is loaded before inserting text
                ensureLineInWindow(cursorLine, true);

                if (hasSelection) {
                    deleteSelection();
                    if (hasComposing) {
                        hasComposing = false;
                        composingLength = 0;
                    }
                    insertTextAtCursor(text.toString());
                } else if (hasComposing) {
                    replaceComposingWith(text);
                    commitComposing(true);
                } else {
                    insertTextAtCursor(text.toString());
                }
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Ensure line is loaded before setting composing text
                ensureLineInWindow(cursorLine, true);

                if (!hasComposing) {
                    composingLine = cursorLine;
                    composingOffset = cursorChar;
                    composingLength = 0;
                    hasComposing = true;
                }
                replaceComposingWith(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (hasSelection) {
                    deleteSelection();
                    return true;
                }
                if (beforeLength > 0) {
                    for (int i = 0; i < beforeLength; i++) deleteCharAtCursor();
                    return true;
                }
                if (afterLength > 0) {
                    for (int i = 0; i < afterLength; i++) deleteForwardAtCursor();
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isDisabled) return true;
        float ex = event.getX();
        float ey = event.getY();
        lastTouchX = ex;
        lastTouchY = ey;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!isFocused()) requestFocus();
                pointerDown = true;
                downX = ex;
                downY = ey;
                movedSinceDown = false;

                if (showPopup && (btnCopyRect.contains(ex, ey) || btnCutRect.contains(ex, ey) || btnPasteRect.contains(ex, ey) || btnSelectAllRect.contains(ex, ey))) {
                    return true;
                }

                if (!scroller.isFinished()) {
                    scroller.computeScrollOffset();
                    scrollX = scroller.getCurrX();
                    scrollY = scroller.getCurrY();
                    scroller.abortAnimation();
                }

                float gx = ex + scrollX - paddingLeft;
                float gy = ey + scrollY;
                if (hasSelection && leftHandleRect.contains(gx, gy)) {
                    draggingHandle = 1;
                    return true;
                } else if (hasSelection && rightHandleRect.contains(gx, gy)) {
                    draggingHandle = 2;
                    return true;
                } else if (isFocused() && !hasSelection && cursorHandleRect.contains(gx, gy)) {
                    draggingHandle = 3;
                    return true;
                }

                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ex - downX) > touchSlop || Math.abs(ey - downY) > touchSlop) movedSinceDown = true;
                if (draggingHandle != 0) {
                    updateHandlePosition(ex, ey);
                    if (draggingHandle == 1 || draggingHandle == 2) {
                        showPopupAtSelection();
                    }
                    float scrollMargin = lineHeight * 2;
                    float scrollSpeed = 25f;
                    autoScrollY = 0;
                    autoScrollX = 0;
                    if (ey < scrollMargin) autoScrollY = -scrollSpeed;
                    else if (ey > (getHeight() - keyboardHeight) - scrollMargin) autoScrollY = scrollSpeed;
                    if (ex < scrollMargin) autoScrollX = -scrollSpeed;
                    else if (ex > getWidth() - scrollMargin) autoScrollX = scrollSpeed;
                    if (autoScrollX != 0 || autoScrollY != 0) {
                        mainHandler.post(autoScrollRunnable);
                    } else {
                        mainHandler.removeCallbacks(autoScrollRunnable);
                    }
                    invalidate();
                    return true;
                }
                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_UP:
                mainHandler.removeCallbacks(autoScrollRunnable);
                pointerDown = false;
                if (showPopup) {
                    if (btnCopyRect.contains(ex, ey)) {
                        copySelectionToClipboard();
                        hasSelection = false;
                        isSelectAllActive = false;
                        hidePopup();
                        invalidate();
                        return true;
                    } else if (btnCutRect.contains(ex, ey)) {
                        cutSelectionToClipboard();
                        return true;
                    } else if (btnPasteRect.contains(ex, ey)) {
                        pasteFromClipboard();
                        return true;
                    } else if (btnSelectAllRect.contains(ex, ey)) {
                        if (!isSelectAllActive) {
                            selectAll();
                        } else {
                            hidePopup();
                        }
                        return true;
                    }
                }
                if (draggingHandle != 0) {
                    if (draggingHandle == 1 || draggingHandle == 2) {
                        showPopupAtSelection();
                    }
                    draggingHandle = 0;
                    invalidate();
                    return true;
                }
                if (movedSinceDown) {
                    if (scroller.isFinished() && hasSelection) {
                        showPopupAtSelection();
                    }
                }
                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(autoScrollRunnable);
                pointerDown = false;
                draggingHandle = 0;
                selecting = false;
                gestureDetector.onTouchEvent(event);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateHandlePosition(float touchX, float touchY) {
        if (isSelectAllActive) {
            if (draggingHandle == 1) {
                int lastLineInDoc = windowStartLine + linesWindow.size() - 1;
                if (lastLineInDoc < 0) lastLineInDoc = 0;
                String lastLine = getLineFromWindowLocal(lastLineInDoc - windowStartLine);
                if (lastLine == null) lastLine = "";
                selEndLine = lastLineInDoc;
                selEndChar = lastLine.length();
            } else if (draggingHandle == 2) {
                selStartLine = 0;
                selStartChar = 0;
            }
        }
        isSelectAllActive = false;
        float viewX = touchX + scrollX - paddingLeft;

        float effectiveTouchY = touchY;
        float viewHeight = getHeight();
        float keyboardTop = viewHeight - keyboardHeight;

        // Constrain the logical touch position to be within the visible (non-keyboard) area
        if (keyboardHeight > 0 && effectiveTouchY > keyboardTop) {
            effectiveTouchY = keyboardTop;
        }
        if (effectiveTouchY < 0) {
            effectiveTouchY = 0;
        }

        float viewY = effectiveTouchY + scrollY;
        int line = Math.max(0, (int) (viewY / lineHeight));

        if (isEof) {
            int lastValidLine = windowStartLine + linesWindow.size() - 1;
            if (line > lastValidLine) {
                line = lastValidLine;
            }
        }

        if (Math.abs(line - windowStartLine) < windowSize + prefetchLines) {
            ensureLineInWindow(line, true);
        }
        int local = line - windowStartLine;
        String ln = getLineFromWindowLocal(local);
        if (ln == null) ln = "";
        int charIndex = getCharIndexForX(ln, viewX);
        if (draggingHandle == 1) {
            selStartLine = line;
            selStartChar = Math.max(0, Math.min(charIndex, ln.length()));
        } else if (draggingHandle == 2) {
            selEndLine = line;
            selEndChar = Math.max(0, Math.min(charIndex, ln.length()));
        } else if (draggingHandle == 3) {
            cursorLine = line;
            cursorChar = Math.max(0, Math.min(charIndex, ln.length()));
            keepCursorVisibleHorizontally();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDisabled) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCursorLeft();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCursorRight();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCursorUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCursorDown();
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (hasSelection) deleteSelection();
                else deleteCharAtCursor();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (hasSelection) deleteSelection();
                else deleteForwardAtCursor();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                if (hasSelection) deleteSelection();
                insertNewlineAtCursor();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void moveCursorLeft() {
        if (isFileCleared) {
            cursorLine = 0;
            cursorChar = 0;
            hasSelection = false;
            isSelectAllActive = false;
            isEntireFileSelected = false;
            hidePopup();
            invalidate();
            keepCursorVisibleHorizontally();
            return;
        }
        if (hasSelection) {
            cursorLine = Math.min(selStartLine, selEndLine);
            cursorChar = Math.min(selStartChar, selEndChar);
        } else if (cursorChar > 0) {
            cursorChar--;
        } else if (cursorLine > 0) {
            cursorLine--;
            if (Math.abs(cursorLine - windowStartLine) < windowSize + prefetchLines) {
                ensureLineInWindow(cursorLine, true);
            }
            String ln = getLineFromWindowLocal(cursorLine - windowStartLine);
            if (ln == null) ln = "";
            cursorChar = ln.length();
        }
        hasSelection = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        hidePopup();
        invalidate();
        keepCursorVisibleHorizontally();

        // If keyboard is visible and we've moved the cursor, ensure it stays visible above keyboard
        if (keyboardHeight > 0) {
            post(() -> ensureCursorVisibleAfterKeyboard());
        }
    }

    private void moveCursorRight() {
        if (isFileCleared) {
            cursorLine = 0;
            cursorChar = 0;
            hasSelection = false;
            isSelectAllActive = false;
            isEntireFileSelected = false;
            hidePopup();
            invalidate();
            keepCursorVisibleHorizontally();
            return;
        }
        if (hasSelection) {
            cursorLine = Math.max(selStartLine, selEndLine);
            cursorChar = Math.max(selStartChar, selEndChar);
        } else {
            if (Math.abs(cursorLine - windowStartLine) < windowSize + prefetchLines) {
                ensureLineInWindow(cursorLine, true);
            }
            String ln = getLineFromWindowLocal(cursorLine - windowStartLine);
            if (ln == null) ln = "";
            if (cursorChar < ln.length()) {
                cursorChar++;
            } else {
                int next = cursorLine + 1;
                if (Math.abs(next - windowStartLine) < windowSize + prefetchLines) {
                    ensureLineInWindow(next, false);
                }
                int local = next - windowStartLine;
                if (local >= 0 && local < linesWindow.size()) {
                    cursorLine = next;
                    cursorChar = 0;
                }
            }
        }
        hasSelection = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        hidePopup();
        invalidate();
        keepCursorVisibleHorizontally();

        // If keyboard is visible and we've moved the cursor, ensure it stays visible above keyboard
        if (keyboardHeight > 0) {
            post(() -> ensureCursorVisibleAfterKeyboard());
        }
    }

    private void moveCursorUp() {
        if (isFileCleared) {
            cursorLine = 0;
            cursorChar = 0;
            hasSelection = false;
            isSelectAllActive = false;
            isEntireFileSelected = false;
            hidePopup();
            invalidate();
            keepCursorVisibleHorizontally();
            return;
        }
        if (hasSelection) {
            cursorLine = Math.min(selStartLine, selEndLine);
            cursorChar = Math.min(selStartChar, selEndChar);
        }
        if (cursorLine > 0) {
            cursorLine--;
            if (Math.abs(cursorLine - windowStartLine) < windowSize + prefetchLines) {
                ensureLineInWindow(cursorLine, true);
            }
            String ln = getLineFromWindowLocal(cursorLine - windowStartLine);
            if (ln == null) ln = "";
            cursorChar = Math.min(cursorChar, ln.length());
        }
        hasSelection = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        hidePopup();
        invalidate();
        keepCursorVisibleHorizontally();

        // If keyboard is visible and we've moved the cursor, ensure it stays visible above keyboard
        if (keyboardHeight > 0) {
            post(() -> ensureCursorVisibleAfterKeyboard());
        }
    }

    private void moveCursorDown() {
        if (isFileCleared) {
            cursorLine = 0;
            cursorChar = 0;
            hasSelection = false;
            isSelectAllActive = false;
            isEntireFileSelected = false;
            hidePopup();
            invalidate();
            keepCursorVisibleHorizontally();
            return;
        }
        if (hasSelection) {
            cursorLine = Math.max(selStartLine, selEndLine);
            cursorChar = Math.max(selStartChar, selEndChar);
        }
        int next = cursorLine + 1;
        if (Math.abs(next - windowStartLine) < windowSize + prefetchLines) {
            ensureLineInWindow(next, false);
        }
        int local = next - windowStartLine;
        if (local >= 0 && local < linesWindow.size()) {
            cursorLine = next;
            String ln = getLineFromWindowLocal(cursorLine - windowStartLine);
            if (ln == null) ln = "";
            cursorChar = Math.min(cursorChar, ln.length());
        }
        hasSelection = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        hidePopup();
        invalidate();
        keepCursorVisibleHorizontally();

        // If keyboard is visible and we've moved the cursor, ensure it stays visible above keyboard
        if (keyboardHeight > 0) {
            post(() -> ensureCursorVisibleAfterKeyboard());
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (focused) {
            if (imm != null) imm.restartInput(this);
        } else {
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            hasComposing = false;
            hasSelection = false;
            hidePopup();
        }
    }

    private void invalidateLineGlobal(int globalLine) {
        float top = (globalLine * lineHeight) - scrollY;
        invalidate(0, (int) Math.floor(top), getWidth(), (int) Math.ceil(top + lineHeight));
    }

    public int getLinesCount() {
        synchronized (lineOffsets) {
            if (isIndexReady && !lineOffsets.isEmpty()) {
                return lineOffsets.size();
            }
        }
        if (isEof) {
            return windowStartLine + linesWindow.size();
        }
        // If we don't have the full file loaded, return the best estimate we have
        if (!linesWindow.isEmpty()) {
            return windowStartLine + linesWindow.size();
        }
        return -1;
    }

    private void clampScrollX() {
        float max = Math.max(0f, getMaxLineWidthInWindow() - (getWidth() - paddingLeft));
        if (scrollX < 0) scrollX = 0;
        if (scrollX > max) scrollX = max;
    }

    private float getMaxLineWidthInWindow() {
        float mx = 0f;
        synchronized (lineWidthCache) {
            if (lineWidthCache.isEmpty()) {
                int firstVisibleLine = Math.max(0, (int) (scrollY / lineHeight));
                int lastVisibleLine = Math.min(
                        windowStartLine + linesWindow.size() - 1,
                        firstVisibleLine + (int) Math.ceil(getHeight() / lineHeight)
                );

                for (int i = firstVisibleLine; i <= lastVisibleLine; i++) {
                    String line = getLineFromWindowLocal(i - windowStartLine);
                    if (line != null) {
                        mx = Math.max(mx, getWidthForLine(i, line));
                    }
                }
            } else {
                for (Float w : lineWidthCache.values()) {
                    if (w != null && w > mx) {
                        mx = w;
                    }
                }
            }
        }
        return mx + 20f;
    }

    private void keepCursorVisibleHorizontally() {
        float cursorYTop = cursorLine * lineHeight;
        float cursorYBottom = cursorYTop + lineHeight;
        int viewHeight = getHeight() - keyboardHeight;
        if (viewHeight <= 0) viewHeight = getHeight();

        float visibleTop = scrollY;
        // Adjust visible bottom to account for bottom padding when cursor is near the end
        float visibleBottom = scrollY + viewHeight;

        // Check if this is near the end of the document and we need to adjust for bottom padding
        if (isEof && cursorLine >= windowStartLine + linesWindow.size() - 1) {
            // When keyboard is visible, ensure the cursor is positioned above it with additional padding
            float effectiveViewHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : viewHeight;
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;

            // For the last line, ensure there's visual padding below the cursor
            float maxScrollWithoutPadding = (windowStartLine + linesWindow.size()) * lineHeight - effectiveViewHeight;
            float targetScroll = cursorYBottom - (effectiveViewHeight - paddingToUse);
            if (targetScroll > maxScrollWithoutPadding) {
                scrollY = Math.max(scrollY, targetScroll);
            } else if (cursorYBottom > visibleBottom - paddingToUse) {
                scrollY = cursorYBottom - (effectiveViewHeight - paddingToUse);
            } else if (cursorYTop < visibleTop) {
                scrollY = cursorYTop;
            }
        } else if (cursorLine >= getLinesCount() - 3) { // Within 3 lines of the end of the file
            // Apply bottom padding when near the end of the file, especially when keyboard is visible
            float effectiveViewHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : viewHeight;
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : MIN_BOTTOM_VISIBLE_SPACE;

            if (cursorYBottom > visibleBottom - paddingToUse) {
                scrollY = cursorYBottom - (effectiveViewHeight - paddingToUse);
            } else if (cursorYTop < visibleTop) {
                scrollY = cursorYTop;
            }
        } else {
            // When keyboard is visible, ensure cursor stays above it with a margin
            if (keyboardHeight > 0) {
                float keyboardTop = getHeight() - keyboardHeight;
                float currentCursorViewY = cursorYBottom - scrollY;

                if (currentCursorViewY >= keyboardTop) {
                    // Adjust scroll to keep cursor above the keyboard with padding
                    float paddingAboveKeyboard = Math.min(lineHeight * 1.5f, keyboardHeight * 0.2f); // More substantial padding
                    scrollY = cursorYBottom - (getHeight() - keyboardHeight - paddingAboveKeyboard);
                }
            }

            if (cursorYBottom > visibleBottom) {
                scrollY = cursorYBottom - viewHeight;
            } else if (cursorYTop < visibleTop) {
                scrollY = cursorYTop;
            }
        }
        clampScrollY();

        // Handle horizontal scrolling even when cursor is outside the loaded window
        String line = null;
        boolean cursorInWindow = (cursorLine >= windowStartLine && cursorLine < windowStartLine + linesWindow.size());

        if (cursorInWindow) {
            line = getLineFromWindowLocal(cursorLine - windowStartLine);
        } else if (!isEof) {
            // Try to load the line if cursor is outside window but not at EOF
            ensureLineInWindow(cursorLine, false);
        }

        if (line == null) {
            line = "";
        }

        String lineUntilCursor = line.substring(0, Math.min(cursorChar, line.length()));
        float cursorX = paint.measureText(lineUntilCursor);
        float visibleWidth = getWidth() - paddingLeft;

        float scrollMargin = 50f;

        if (cursorX < scrollX + scrollMargin) {
            scrollX = Math.max(0, cursorX - scrollMargin);
        } else if (cursorX > scrollX + visibleWidth - scrollMargin) {
            scrollX = cursorX - visibleWidth + scrollMargin;
        }
        clampScrollX();

        invalidate();
    }

    private long[] buildIndexJava(String filepath) {
        List<Long> offsetsList = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(filepath, "r")) {
            offsetsList.add(0L);
            long pos = 0;
            long length = raf.length();
            byte[] buffer = new byte[8192];

            while (pos < length) {
                raf.seek(pos);
                int bytesRead = raf.read(buffer);
                if (bytesRead == -1) break;

                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == '\n') {
                        long nextLineOffset = pos + i + 1;
                        offsetsList.add(nextLineOffset);
                    }
                }
                pos += bytesRead;
            }

            return offsetsList.stream().mapToLong(Long::longValue).toArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void cancelAndCloseReader() {
        ioHandler.post(() -> {
            try {
                if (readerForFile != null) {
                    readerForFile.close();
                    readerForFile = null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void release() {
        cancelAndCloseReader();
        ioThread.quitSafely();
    }
}
