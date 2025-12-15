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
import android.view.ScaleGestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class PopEditText extends View {

    // paint & metrics
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lineHeight;
    private float paddingLeft = 10f; // Made non-final for line numbers

    // --- Line Number State ---
    private boolean showLineNumbers = false;
    private float lineNumbersGutterWidth = 0f;
    private final Paint lineNumbersPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterPaint = new Paint();
    private final Paint gutterSeparatorPaint = new Paint();
    private float gutterSeparatorWidth;
    private boolean isRtl = false;
    private final Rect textBounds = new Rect();
    private static final float GUTTER_TEXT_PADDING = 20f;

    // visual padding constants
    private static final float BOTTOM_SCROLL_OFFSET = 100f; // Visual padding below last line
    private static final float MIN_BOTTOM_VISIBLE_SPACE = 50f; // Minimum space to show below last line

    // scroll state (pixels)
    private float scrollY = 0f;
    private float scrollX = 0f;

    // sliding window
    private final List<String> linesWindow = new ArrayList<>();
    private int windowStartLine = 0;
    private int windowSize = 2000;
    private int prefetchLines = 1000;

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
    private float currentMaxWindowLineWidth = 0f;
    private float globalMaxLineWidth = 0f;

    // --- Cursor Blinking State ---
    private boolean isCursorVisible = true;
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFocused() && !hasSelection) {
                isCursorVisible = !isCursorVisible;
                invalidate(); // Using full invalidate for simplicity
                mainHandler.postDelayed(this, 500);
            }
        }
    };
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;

    // --- Zoom State ---
    private boolean isZoomEnabled = true;
    private ScaleGestureDetector scaleGestureDetector;
    private static final float MIN_TEXT_SIZE = 10f;
    private static final float MAX_TEXT_SIZE = 120f;

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
    private RectF btnDeleteRect = new RectF();
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
    private float cursorWidth = 6f;
    private int selectionHighlightColor = 0x8033B5E5;
    private int cursorAndHandlesColor = 0xFF2196F3;

    // --- Current Line Highlight State ---
    private boolean highlightCurrentLine = true;
    private int currentLineHighlightColor = 0x202196F3; // Default: translucent gray (more visible)
    private final Paint currentLinePaint = new Paint();

    private int draggingHandle = 0;
    private volatile boolean isWindowLoading = false;

    private boolean isDisabled = false;
    private final AtomicInteger goToLineVersion = new AtomicInteger(0);

    // Loading circle variables
    private boolean showLoadingCircle = false;
    private float loadingCircleRadius = 40f;
    private int loadingCircleColor = 0xFF3F51B5;
    private float loadingCircleRotation = 0f;
    private ValueAnimator rotationAnimator;

    // index
    private final Object lineOffsetsLock = new Object();
    private long[] lineOffsets = new long[0];
    private volatile boolean isIndexReady = false;
    private volatile boolean isIndexBuilding = false;

    // edit version (to ignore old rewrite results)
    private final AtomicInteger editVersion = new AtomicInteger(0);

    // Large edit UI (brief busy indicator)
    private static final int LARGE_EDIT_LINES = 8000; // show spinner/disable for very large edits
    private static final int HIDE_COPY_CUT_LINES = 20000;
    private final AtomicInteger largeEditUiToken = new AtomicInteger(0);
    private final Runnable largeEditUiWatchdog = new Runnable() {
        @Override public void run() {
            // Safety: never allow spinner/disable to get stuck forever
            endLargeEditUi(false);
        }
    };

    // Direct read cache for fast fling rendering when window hasn't loaded yet (index-based)
    private final LinkedHashMap<Integer, String> directLineCache =
            new LinkedHashMap<Integer, String>(600, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > 600;
                }
            };


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
        paint.setSubpixelText(true);
        paint.setHinting(Paint.HINTING_ON);
        paint.setUnderlineText(false); // Explicitly disable underlines to fix visual artifact
        lineHeight = paint.getFontSpacing();

        // Initialization for line numbers
        lineNumbersPaint.setTextAlign(Paint.Align.RIGHT);
        lineNumbersPaint.setColor(0xFF888888); // Default gray color
        lineNumbersPaint.setTextSize(paint.getTextSize());
        gutterPaint.setColor(0xFFFAFAFA); // Default light gray background

        float density = getContext().getResources().getDisplayMetrics().density;
        gutterSeparatorWidth = 4 * density;
        gutterSeparatorPaint.setColor(0xFF555555);
        currentLinePaint.setColor(currentLineHighlightColor);


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
                float x = e.getX() + scrollX - getTextStartX();
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
                isEntireFileSelected = false;
                selecting = true;
                cursorLine = line;
                cursorChar = selEndChar;
                showPopupAtSelection();
                resetCursorBlink();
                invalidate();
                showKeyboard();
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (hasSelection) {
                    hasSelection = false;
                    isSelectAllActive = false;
                    isEntireFileSelected = false;
                }
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - getTextStartX();
                int line = Math.max(0, (int) (y / lineHeight));

                if (isEof && line >= windowStartLine + linesWindow.size() && !linesWindow.isEmpty()) {
                    cursorLine = windowStartLine + linesWindow.size() - 1;
                    String lastLineText = getLineTextForRender(cursorLine);
                    cursorChar = lastLineText.length();
                } else {
                    ensureLineInWindow(line, true);
                    String ln = getLineTextForRender(line);
                    int charIndex = getCharIndexForX(ln, x);
                    cursorLine = line;
                    cursorChar = Math.max(0, Math.min(charIndex, ln.length()));
                }

                hidePopup();
                selecting = false;
                invalidate();
                resetCursorBlink();
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
                if (Math.abs(distanceY) > lineHeight * 6f) {
                    checkAndLoadWindow();
                } else {
                    postDelayed(delayedWindowCheck, 60);
                }

                if (showPopup) hidePopup();
                resetCursorBlink();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                int startX = Math.round(scrollX);
                int startY = Math.round(scrollY);
                int minX = 0;
                int maxX = Math.max(0, Math.round(getMaxLineWidthInWindow() - (getWidth() - getTextStartX())));
                int minY = 0;

                float maxScrollYFloat;
                float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();

                if (isEof) {
                    float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
                    maxScrollYFloat = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight - (effectiveHeight - paddingToUse));
                } else {
                    float virtualExtraSpace = Math.max(prefetchLines * lineHeight, 2000f);
                    maxScrollYFloat = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight + virtualExtraSpace - effectiveHeight);
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
                float x = e.getX() + scrollX - getTextStartX();
                int line = Math.max(0, (int) (y / lineHeight));
                ensureLineInWindow(line, true);
                String ln = getLineTextForRender(line);
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
                isEntireFileSelected = false;
                selecting = true;
                cursorLine = line;
                cursorChar = selEndChar;
                showPopupAtSelection();
                resetCursorBlink();
                invalidate();
                showKeyboard();
                return true;
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isZoomEnabled) {
                    return false;
                }
                float currentSize = paint.getTextSize();
                float newSize = currentSize * detector.getScaleFactor();

                newSize = Math.max(MIN_TEXT_SIZE, Math.min(newSize, MAX_TEXT_SIZE));

                if (Math.abs(newSize - currentSize) > 0.1f) {
                    setTextSize(newSize);
                }
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

            if (newKeyboardHeight != keyboardHeight) {
                keyboardHeight = newKeyboardHeight;
                post(this::keepCursorVisibleHorizontally);
            }
        });
    }

    // --- Public APIs for Line Numbers ---

    public void setShowLineNumbers(boolean show) {
        if (this.showLineNumbers == show) return;
        this.showLineNumbers = show;
        requestLayout(); // Recalculate gutter width
        invalidate();    // Redraw
    }

    public void setLineNumberColor(int color) {
        lineNumbersPaint.setColor(color);
        if (showLineNumbers) invalidate();
    }

    public void setGutterBackgroundColor(int color) {
        gutterPaint.setColor(color);
        if (showLineNumbers) invalidate();
    }

    public void setSelectionHighlightColor(int color) {
        if (this.selectionHighlightColor == color) return;
        this.selectionHighlightColor = color;
        if (hasSelection) invalidate();
    }

    public void setCursorAndHandlesColor(int color) {
        if (this.cursorAndHandlesColor == color) return;
        this.cursorAndHandlesColor = color;
        invalidate();
    }

    public void setCursorWidth(float width) {
        if (this.cursorWidth == width) return;
        this.cursorWidth = width;
        invalidate();
    }

    public void setHighlightCurrentLine(boolean enabled) {
        if (this.highlightCurrentLine == enabled) return;
        this.highlightCurrentLine = enabled;
        invalidate();
    }

    public void setCurrentLineHighlightColor(int color) {
        this.currentLineHighlightColor = color;
        this.currentLinePaint.setColor(color);
        if (highlightCurrentLine) invalidate();
    }

    public void setGutterSeparatorColor(int color) {
        gutterSeparatorPaint.setColor(color);
        if (showLineNumbers) {
            invalidate();
        }
    }

    public void setLayoutDirection(boolean isRtl) {
        if (this.isRtl == isRtl) return;
        this.isRtl = isRtl;
        lineNumbersPaint.setTextAlign(isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        requestLayout();
        invalidate();
    }
    
    public void setTextSize(float size) {
        paint.setTextSize(size);
        lineNumbersPaint.setTextSize(size);
        lineHeight = paint.getFontSpacing();
        recalculateMaxLineWidth();
        requestLayout(); // Gutter width might change
        invalidate();
    }

    // --- Layout and Measurement ---

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (showLineNumbers) {
            int maxLines = 0;
            if (isIndexReady) {
                maxLines = lineOffsets.length;
            } else if (isEof) {
                maxLines = windowStartLine + linesWindow.size();
            } else {
                maxLines = 999999; // Wider fallback for width calculation until index is ready
            }
            String maxLineNum = String.valueOf(maxLines);
            lineNumbersGutterWidth = lineNumbersPaint.measureText(maxLineNum) + (GUTTER_TEXT_PADDING * 2) + gutterSeparatorWidth;
        } else {
            lineNumbersGutterWidth = 0f;
        }
    }
    
    private float getTextStartX() {
        return isRtl ? paddingLeft : paddingLeft + lineNumbersGutterWidth;
    }
    
    private float getGutterStartX() {
        return isRtl ? getWidth() - lineNumbersGutterWidth : 0;
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

        // Calculate visible line range
        int firstVisibleLine = (int) (scrollY / lineHeight);
        if (firstVisibleLine < 0) firstVisibleLine = 0;
        int lastVisibleLine = firstVisibleLine + (int) Math.ceil(getHeight() / lineHeight) + 5;
        if (isEof) {
            synchronized(linesWindow) {
                int lastDocLine = Math.max(0, windowStartLine + linesWindow.size() - 1);
                lastVisibleLine = Math.min(lastVisibleLine, lastDocLine);
            }
        }
        if (lastVisibleLine < firstVisibleLine) lastVisibleLine = firstVisibleLine;

        maybeKickWindowLoad(firstVisibleLine);

        // --- 1. Draw fixed gutter background ---
        if (showLineNumbers) {
            canvas.drawRect(getGutterStartX(), 0, getGutterStartX() + lineNumbersGutterWidth, getHeight(), gutterPaint);

            // Draw separator line
            float separatorLeft;
            if (isRtl) {
                // Separator is on the left side of the gutter (inner edge)
                separatorLeft = getGutterStartX();
            } else {
                // Separator is on the right side of the gutter (inner edge)
                separatorLeft = getGutterStartX() + lineNumbersGutterWidth - gutterSeparatorWidth;
            }
            canvas.drawRect(separatorLeft, 0, separatorLeft + gutterSeparatorWidth, getHeight(), gutterSeparatorPaint);
        }

        // --- 2. Draw line numbers (vertically scrolled) ---
        if (showLineNumbers) {
            canvas.save();
            canvas.translate(0, -scrollY);

            float lineNumX;
            if (isRtl) {
                lineNumX = getGutterStartX() + GUTTER_TEXT_PADDING;
            } else {
                lineNumX = getGutterStartX() + lineNumbersGutterWidth - GUTTER_TEXT_PADDING;
            }

            for (int i = firstVisibleLine; i <= lastVisibleLine; i++) {
                String lineNum = String.valueOf(i + 1);
                float y = Math.round(((i + 1) * lineHeight) - paint.descent());
                canvas.drawText(lineNum, lineNumX, y, lineNumbersPaint);
            }
            canvas.restore();
        }

        // --- 3. Draw main text content (scrolled) ---
        canvas.save();
        // Clip the text area so it doesn't draw over the gutter
        if (isRtl) {
            canvas.clipRect(0, 0, getWidth() - lineNumbersGutterWidth, getHeight());
        } else {
            canvas.clipRect(lineNumbersGutterWidth, 0, getWidth(), getHeight());
        }
        canvas.translate(getTextStartX() - scrollX, -scrollY);

        // --- This is the original text, selection, and handle drawing logic ---
        Paint selPaint = null;
        if (hasSelection) {
            selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            selPaint.setColor(selectionHighlightColor);
        }

        java.util.HashMap<Integer, String> directLines = null;
        if (isIndexReady && sourceFile != null && sourceFile.exists()) {
            boolean needDirect = (firstVisibleLine < windowStartLine) ||
                    (firstVisibleLine >= windowStartLine + linesWindow.size()) ||
                    (lastVisibleLine >= windowStartLine + linesWindow.size());

            if (needDirect) {
                directLines = new java.util.HashMap<>();
                if (firstVisibleLine < windowStartLine) {
                    populateDirectLinesForRange(firstVisibleLine, Math.min(lastVisibleLine, windowStartLine - 1), directLines);
                }
                int winEnd = windowStartLine + linesWindow.size() - 1;
                if (lastVisibleLine > winEnd) {
                    populateDirectLinesForRange(Math.max(firstVisibleLine, winEnd + 1), lastVisibleLine, directLines);
                }
                if (directLines.isEmpty() && (firstVisibleLine < windowStartLine || firstVisibleLine >= windowStartLine + linesWindow.size())) {
                    populateDirectLinesForRange(firstVisibleLine, lastVisibleLine, directLines);
                }
            }
        }

        for (int globalLine = firstVisibleLine; globalLine <= lastVisibleLine; globalLine++) {
            String line = getLineTextForRenderWithDirect(globalLine, directLines);

            // Highlight the current line, only if there is no selection
            if (highlightCurrentLine && globalLine == cursorLine && !hasSelection) {
                float top = Math.round(globalLine * lineHeight);
                float bottom = Math.round((globalLine + 1) * lineHeight);
                canvas.drawRect(-paddingLeft, top, Math.max(getMaxLineWidthInWindow(), getWidth() + scrollX), bottom, currentLinePaint);
            }

            if (hasSelection && selPaint != null) {
                float top = Math.round(globalLine * lineHeight);
                float bottom = Math.round((globalLine + 1) * lineHeight);

                if (isSelectAllActive) {
                    boolean lineExists = (isEof) ? (globalLine <= windowStartLine + linesWindow.size() - 1) : true;
                    if (lineExists) {
                        canvas.drawRect(0, top, currentMaxWindowLineWidth, bottom, selPaint);
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
                        float left, right;
                        if (startLine == endLine) {
                            left = paint.measureText(line, 0, Math.min(startChar, line.length()));
                            right = paint.measureText(line, 0, Math.min(endChar, line.length()));
                        } else {
                            if (globalLine == startLine) {
                                left = paint.measureText(line, 0, Math.min(startChar, line.length()));
                                right = currentMaxWindowLineWidth;
                            } else if (globalLine == endLine) {
                                left = 0;
                                right = paint.measureText(line, 0, Math.min(endChar, line.length()));
                                if (line.length() == 0) right = currentMaxWindowLineWidth;
                            } else {
                                left = 0;
                                right = currentMaxWindowLineWidth;
                            }
                        }
                        if (right > left) canvas.drawRect(left, top, right, bottom, selPaint);
                    }
                }
            }

            float y = Math.round(((globalLine + 1) * lineHeight) - paint.descent());
            canvas.drawText(line, 0, y, paint);
        }

        if (isFocused() && !hasSelection) {
            String cursorLineText = getLineTextForRender(cursorLine);
            int safeChar = Math.min(cursorChar, cursorLineText.length());
            float cursorX = paint.measureText(cursorLineText, 0, safeChar);
            float cursorY = cursorLine * lineHeight;
            if (isCursorVisible) {
                Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                caretPaint.setColor(cursorAndHandlesColor);
                caretPaint.setStrokeWidth(cursorWidth);
                canvas.drawLine(cursorX, cursorY, cursorX, cursorY + lineHeight, caretPaint);
            }
            Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handlePaint.setColor(cursorAndHandlesColor);
            drawTeardropHandle(canvas, cursorX, cursorY + lineHeight, handlePaint);
            cursorHandleRect.set(cursorX - handleRadius, cursorY + lineHeight, cursorX + handleRadius, cursorY + lineHeight + handleRadius * 2);
        }

        if (hasSelection) {
            Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handlePaint.setColor(cursorAndHandlesColor);
            String startLineText = getLineTextForRender(selStartLine);
            float startX = paint.measureText(startLineText, 0, Math.min(selStartChar, startLineText.length()));
            float startY = selStartLine * lineHeight + lineHeight;
            drawTeardropHandle(canvas, startX, startY, handlePaint);
            leftHandleRect.set(startX - handleRadius, startY, startX + handleRadius, startY + handleRadius * 2);
            String endLineText = getLineTextForRender(selEndLine);
            float endX = paint.measureText(endLineText, 0, Math.min(selEndChar, endLineText.length()));
            float endY = selEndLine * lineHeight + lineHeight;
            drawTeardropHandle(canvas, endX, endY, handlePaint);
            rightHandleRect.set(endX - handleRadius, endY, endX + handleRadius, endY + handleRadius * 2);
        }

        canvas.restore();
        // --- End of main text content drawing ---


        // --- 4. Draw overlays (popups, loading circle, etc.) ---
        if (showPopup) drawPopup(canvas);

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
            canvas.drawArc(rect, 0, 270, false, circlePaint);
            canvas.restore();
        }
    }

    private void maybeKickWindowLoad(int firstVisibleLine) {
        if (sourceFile == null && !isFileCleared) {
            // in-memory only: no need
            return;
        }
        if (isWindowLoading) return;

        boolean inside = firstVisibleLine >= windowStartLine && firstVisibleLine < windowStartLine + linesWindow.size();
        if (!inside) {
            int targetStart = Math.max(0, firstVisibleLine - (windowSize / 4));
            loadWindowAround(targetStart, null);
        }
    }

    private void drawTeardropHandle(Canvas canvas, float cx, float cy, Paint paint) {
        Path path = new Path();
        path.addOval(cx - handleRadius, cy, cx + handleRadius, cy + handleRadius * 2, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    private void drawPopup(Canvas canvas) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFF424242);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTextSize(30f);
        txt.setColor(0xFFFFFFFF);

        final boolean hideCopyCut = shouldHideCopyCutForSelection();

        // reset rects (so .contains() is safe when buttons are hidden)
        btnCopyRect.setEmpty();
        btnCutRect.setEmpty();
        btnPasteRect.setEmpty();
        btnDeleteRect.setEmpty();
        btnSelectAllRect.setEmpty();

        // Buttons order
        final List<String> labels = new ArrayList<>();
        if (!hideCopyCut) {
            labels.add("Copy");
            labels.add("Cut");
        }
        labels.add("Paste");
        labels.add("Delete");
        labels.add("Select All");

        final int btnCount = labels.size();
        float totalWidth = (btnWidth * btnCount) + (btnSpacing * (btnCount - 1)) + (popupPadding * 2);
        float totalHeight = btnHeight + (popupPadding * 2);

        // Calculate the anchor point for the popup relative to the view.
        // This should be the position of the selection handle.
        String ln = getLineTextForRender(selEndLine);
        float selX = paint.measureText(ln, 0, Math.max(0, Math.min(selEndChar, ln.length())));
        
        // Handle X position: center of the handle is at getTextStartX() + selX - scrollX
        float handleCenterX = getTextStartX() + selX - scrollX;
        
        // Handle Y position: The handle teardrop starts at selEndLine * lineHeight + lineHeight (bottom of line)
        // and extends downwards by handleRadius * 2. So the bottom of the handle is:
        float handleBottomY = selEndLine * lineHeight - scrollY + lineHeight + handleRadius * 2;

        float proposedLeft = handleCenterX - totalWidth / 2f;

        // Clamp horizontal position to screen bounds
        if (proposedLeft < 0) proposedLeft = 0;
        if (proposedLeft + totalWidth > getWidth()) proposedLeft = getWidth() - totalWidth;

        // Calculate vertical positions:
        // 1. Above the handle
        float topAboveHandle = handleBottomY - totalHeight - (lineHeight * 0.5f); // Half a line height padding
        // 2. Below the handle
        float topBelowHandle = handleBottomY + (lineHeight * 0.5f); // Half a line height padding

        float finalTop;
        // The effective bottom boundary for the popup (top of keyboard or bottom of view)
        float visibleBottomBound = getHeight() - keyboardHeight;

        // Attempt to place above handle first
        if (topAboveHandle >= 0 && (topAboveHandle + totalHeight <= visibleBottomBound)) {
            finalTop = topAboveHandle;
        } else if (topBelowHandle + totalHeight <= visibleBottomBound) {
            // Doesn't fit above, try below (if it fits above keyboard)
            finalTop = topBelowHandle;
        } else {
            // Neither above nor below the handle fits entirely within the visible area above the keyboard.
            // As a last resort, place it as high as possible without going into the keyboard area.
            finalTop = visibleBottomBound - totalHeight - popupPadding;
            if (finalTop < 0) finalTop = 0; // If even this goes off-screen, place at top
        }

        popupRect.set(proposedLeft, finalTop, proposedLeft + totalWidth, finalTop + totalHeight);
        canvas.drawRoundRect(popupRect, popupCorner, popupCorner, bgPaint);

        float bx = popupRect.left + popupPadding;
        float by = popupRect.top + popupPadding;

        for (String label : labels) {
            RectF r;
            switch (label) {
                case "Copy": r = btnCopyRect; break;
                case "Cut": r = btnCutRect; break;
                case "Paste": r = btnPasteRect; break;
                case "Delete": r = btnDeleteRect; break;
                default: r = btnSelectAllRect; break;
            }
            r.set(bx, by, bx + btnWidth, by + btnHeight);
            drawButton(canvas, r, label, txt);
            bx += btnWidth + btnSpacing;
        }
    }

    private boolean shouldHideCopyCutForSelection() {
        if (!hasSelection) return true;

        int sL = selStartLine, eL = selEndLine;
        if (sL > eL) { int t = sL; sL = eL; eL = t; }
        long lines = (long) eL - (long) sL + 1L;
        return lines > HIDE_COPY_CUT_LINES;
    }

    private void drawButton(Canvas canvas, RectF r, String label, Paint txtPaint) {
        float textWidth = txtPaint.measureText(label);
        float cx = r.centerX();
        float cy = r.centerY() - ((txtPaint.descent() + txtPaint.ascent()) / 2f);
        canvas.drawText(label, cx - textWidth / 2f, cy, txtPaint);
    }

    private void showPopupAtSelection() {
        if (!hasSelection) return;
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
            removeCallbacks(delayedWindowCheck);
            maybeKickWindowLoad((int) (scrollY / lineHeight));
            postDelayed(delayedWindowCheck, 40);
            postInvalidateOnAnimation();
        } else {
            if (scrollerIsScrolling) {
                scrollerIsScrolling = false;
                checkAndLoadWindow();
                if (hasSelection) showPopupAtSelection();
            }
        }
    }

    private void clampScrollY() {
        if (isWindowLoading && scrollY < windowStartLine * lineHeight) {
            scrollY = windowStartLine * lineHeight;
            if (!scroller.isFinished()) scroller.abortAnimation();
        }

        if (scrollY < 0) scrollY = 0;

        float maxScroll;
        float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();

        if (isEof) {
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
            maxScroll = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight - (effectiveHeight - paddingToUse));
        } else {
            float virtualExtraSpace = Math.max(prefetchLines * lineHeight, 2000f);
            maxScroll = Math.max(0f, (windowStartLine + linesWindow.size()) * lineHeight + virtualExtraSpace - effectiveHeight);
        }

        if (scrollY > maxScroll) {
            scrollY = maxScroll;
            if (isEof && !scroller.isFinished()) scroller.abortAnimation();
        }
    }

    private void checkAndLoadWindow() {
        if (sourceFile == null && !isFileCleared) return;
        if (getWidth() == 0 || getHeight() == 0) return;
        if (isWindowLoading) return;

        int firstVisibleLine = (int) (scrollY / lineHeight);
        int lastVisibleLine = firstVisibleLine + (int) Math.ceil(getHeight() / lineHeight);
        int buffer = prefetchLines / 2;

        firstVisibleLine = Math.max(0, firstVisibleLine);
        lastVisibleLine = Math.max(firstVisibleLine, lastVisibleLine);

        int expandedBuffer = Math.max(buffer, (int) Math.ceil(getHeight() / lineHeight) + 100);

        if (!isEof && lastVisibleLine >= windowStartLine + linesWindow.size() - expandedBuffer) {
            int targetStart = Math.max(0, firstVisibleLine - prefetchLines / 2);
            loadWindowAround(targetStart, null);
        } else if (firstVisibleLine < windowStartLine + buffer && windowStartLine > 0) {
            int targetStart = Math.max(0, firstVisibleLine - prefetchLines);
            loadWindowAround(targetStart, null);
        } else if (Math.abs(firstVisibleLine - windowStartLine) > windowSize) {
            int targetStart = Math.max(0, firstVisibleLine - windowSize / 4);
            loadWindowAround(targetStart, null);
        }
    }

    private void loadWindowAround(int startLine, @Nullable Runnable onComplete) {
        if (isWindowLoading) return;

        // FIX: If the file has been "cleared" (e.g., via select-all -> delete),
        // the editor is in a pure in-memory state. The `linesWindow` holds the
        // entire document. There is nothing to "load" from a file, so we should
        // simply do nothing. The previous logic incorrectly reset the window.
        if (isFileCleared) {
            if (onComplete != null) {
                post(onComplete);
            }
            return;
        }

        if (sourceFile == null) {
            if (onComplete != null) post(onComplete);
            return;
        }

        isWindowLoading = true;
        final int taskVersion = ioTaskVersion.incrementAndGet();
        final int requestedStart = Math.max(0, startLine);

        ioHandler.post(() -> {
            try {
                if (taskVersion != ioTaskVersion.get()) {
                    post(() -> { isWindowLoading = false; checkAndLoadWindow(); });
                    return;
                }

                int actualStart = requestedStart;

                // If we have an index, clamp the window start to the last existing line.
                if (isIndexReady) {
                    synchronized (lineOffsetsLock) {
                        if (lineOffsets.length > 0 && actualStart >= lineOffsets.length) {
                            actualStart = Math.max(0, lineOffsets.length - 1);
                        }
                    }
                }

                List<String> newWin = new ArrayList<>();
                BufferedReader br;

                if (isIndexReady) {
                    try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                        long fileLen = raf.length();
                        long off;
                        synchronized (lineOffsetsLock) {
                            if (lineOffsets.length > 0 && actualStart < lineOffsets.length) off = lineOffsets[actualStart];
                            else off = fileLen;
                        }
                        off = Math.max(0, Math.min(off, fileLen));
                        raf.seek(off);

                        br = new BufferedReader(
                                new java.io.InputStreamReader(new FileInputStream(raf.getFD()), StandardCharsets.UTF_8),
                                8192
                        );

                        String ln;
                        while (newWin.size() < windowSize + prefetchLines && (ln = br.readLine()) != null) {
                            newWin.add(ln);
                        }
                    }
                } else {
                    // fallback: sequential read from start (slower, but only used when index not ready)
                    br = reopenReaderAtStart();
                    if (br == null) {
                        if (onComplete != null) post(onComplete);
                        post(() -> isWindowLoading = false);
                        return;
                    }

                    int skipped = 0;
                    for (; skipped < actualStart; skipped++) {
                        if (br.readLine() == null) break;
                    }
                    actualStart = skipped;

                    String ln;
                    while (newWin.size() < windowSize + prefetchLines && (ln = br.readLine()) != null) {
                        newWin.add(ln);
                    }
                    try { br.close(); } catch (Exception ignored) {}
                }

                // Guarantee a non-empty window.
                if (newWin.isEmpty()) {
                    newWin.add("");
                    actualStart = 0;
                }

                boolean eof = newWin.size() < windowSize + prefetchLines;

                // Overlay in-memory edits for currently loaded lines
                synchronized (modifiedLines) {
                    for (int i = 0; i < newWin.size(); i++) {
                        int globalLineNum = actualStart + i;
                        if (modifiedLines.containsKey(globalLineNum)) {
                            String modifiedLine = modifiedLines.get(globalLineNum);
                            if (modifiedLine != null) newWin.set(i, modifiedLine);
                        }
                    }
                }

                if (taskVersion != ioTaskVersion.get()) {
                    post(() -> { isWindowLoading = false; checkAndLoadWindow(); });
                    return;
                }

                final int finalStart = actualStart;
                post(() -> {
                    isWindowLoading = false;
                    if (taskVersion != ioTaskVersion.get()) {
                        checkAndLoadWindow();
                        return;
                    }
                    synchronized (linesWindow) {
                        linesWindow.clear();
                        linesWindow.addAll(newWin);
                        windowStartLine = finalStart;
                        isEof = eof;
                    }
                    recalculateMaxLineWidth();
                    invalidate();
                    if (onComplete != null) onComplete.run();
                });
            } catch (Exception e) {
                e.printStackTrace();
                post(() -> { isWindowLoading = false; if (onComplete != null) onComplete.run(); });
            }
        });
    }

    private void buildFileIndex() {
        if (sourceFile == null || !sourceFile.exists()) {
            isIndexReady = false;
            isIndexBuilding = false;
            return;
        }
        isIndexBuilding = true;
        final int taskVersion = ioTaskVersion.get();
        ioHandler.post(() -> {
            long[] offsets = buildIndexJava(sourceFile.getAbsolutePath());
            if (taskVersion != ioTaskVersion.get()) { isIndexBuilding = false; return; }
            if (offsets != null) {
                synchronized (lineOffsetsLock) {
                    if (taskVersion == ioTaskVersion.get()) {
                        lineOffsets = offsets;
                        isIndexReady = true;
                        // When index is ready, we know the true line count.
                        // We must re-measure to calculate the correct gutter width.
                        post(PopEditText.this::requestLayout);
                    }
                }
            } else {
                synchronized (lineOffsetsLock) { isIndexReady = false; }
            }
            isIndexBuilding = false;
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
        isEntireFileSelected = false;

        sourceFile = file;
        windowStartLine = 0;
        synchronized (linesWindow) {
            linesWindow.clear();
        }
        synchronized (modifiedLines) { modifiedLines.clear(); }
        synchronized (lineWidthCache) { lineWidthCache.clear(); }
        currentMaxWindowLineWidth = 0f;
        globalMaxLineWidth = 0f;
        synchronized (lineOffsetsLock) { lineOffsets = new long[0]; }
        isIndexReady = false;

        cursorLine = 0;
        cursorChar = 0;
        isEof = false;
        scrollY = 0;
        scrollX = 0;

        loadWindowAround(0, null);
        ioHandler.post(this::buildFileIndex);
        requestLayout();
        invalidate();
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

    public void setZoomEnabled(boolean enabled) {
        this.isZoomEnabled = enabled;
    }

    public void setDisable(boolean disable) {
        this.isDisabled = disable;
        if (disable) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
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
            if (!rotationAnimator.isRunning()) rotationAnimator.start();
        } else {
            if (rotationAnimator != null && rotationAnimator.isRunning()) rotationAnimator.cancel();
            loadingCircleRotation = 0f;
        }
        invalidate();
    }


private boolean shouldShowLargeEditUi(int sL, int eL, boolean isSelectAllLike) {
    int span = Math.abs(eL - sL) + 1;
    return isSelectAllLike || span >= LARGE_EDIT_LINES;
}

private void beginLargeEditUiIfNeeded(boolean enable, int sL, int eL, boolean isSelectAllLike) {
    if (!enable) return;
    if (!shouldShowLargeEditUi(sL, eL, isSelectAllLike)) return;

    final int token = largeEditUiToken.incrementAndGet();
    setDisable(true);
    showLoadingCircle(true);

    // Watchdog: force hide after a short time in case any path forgets to hide.
    mainHandler.removeCallbacks(largeEditUiWatchdog);
    mainHandler.postDelayed(largeEditUiWatchdog, 1500);

    // Also ensure token validity for later hides.
    post(() -> {
        if (token != largeEditUiToken.get()) return;
    });
}

private void endLargeEditUi(boolean invalidate) {
    // Advance token so any pending watchdog is ignored, then hide.
    largeEditUiToken.incrementAndGet();
    mainHandler.removeCallbacks(largeEditUiWatchdog);
    setDisable(false);
    showLoadingCircle(false);
    if (invalidate) invalidate();
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
            isEntireFileSelected = false;
            selecting = false;
            hidePopup();
        }

        final int requestedLine = Math.max(0, line - 1);
        final int requestedCol = Math.max(0, col - 1);

        Integer knownTotal = null;

        if (isFileCleared) {
            knownTotal = 1;
        } else if (sourceFile == null) {
            // In-memory mode: the "document" is exactly what we have in memory.
            synchronized (linesWindow) {
                knownTotal = Math.max(1, windowStartLine + linesWindow.size());
            }
        } else if (isIndexReady) {
            synchronized (lineOffsetsLock) {
                knownTotal = Math.max(1, lineOffsets.length);
            }
        } else if (isEof) {
            synchronized (linesWindow) {
                knownTotal = Math.max(1, windowStartLine + linesWindow.size());
            }
        }

        if (knownTotal != null) {
            int clampedLine = Math.min(requestedLine, Math.max(0, knownTotal - 1));
            proceedGoToLineClamped(currentGoToLineVersion, clampedLine, requestedCol);
        } else {
            // Index not ready and not at EOF: count lines once to clamp the target line.
            countTotalLines(totalLines -> {
                if (currentGoToLineVersion != goToLineVersion.get()) return;
                int total = (totalLines > 0) ? totalLines : (requestedLine + 1);
                int clampedLine = Math.min(requestedLine, Math.max(0, total - 1));
                proceedGoToLineClamped(currentGoToLineVersion, clampedLine, requestedCol);
            });
        }
    }

    private void proceedGoToLineClamped(final int currentGoToLineVersion, final int targetLine, final int targetCol) {
        // If a window load is already in progress, retry shortly to avoid getting stuck in a disabled/loading state.
        if (isWindowLoading && sourceFile != null &&
                !(targetLine >= windowStartLine && targetLine < windowStartLine + linesWindow.size())) {
            mainHandler.postDelayed(() -> {
                if (currentGoToLineVersion != goToLineVersion.get()) return;
                proceedGoToLineClamped(currentGoToLineVersion, targetLine, targetCol);
            }, 30);
            return;
        }

        Runnable completionAction = () -> {
            if (currentGoToLineVersion != goToLineVersion.get()) return;

            if (isFileCleared) {
                cursorLine = 0;
                cursorChar = 0;
            } else {
                cursorLine = targetLine;

                if (cursorLine >= windowStartLine && cursorLine < windowStartLine + linesWindow.size()) {
                    String lineText = getLineTextForRender(cursorLine);
                    cursorChar = Math.max(0, Math.min(targetCol, lineText.length()));
                } else if (isEof) {
                    int lastLineInDoc = windowStartLine + linesWindow.size() - 1;
                    if (cursorLine > lastLineInDoc) cursorLine = Math.max(0, lastLineInDoc);
                    String lineText = getLineTextForRender(cursorLine);
                    cursorChar = Math.max(0, Math.min(targetCol, lineText.length()));
                } else {
                    cursorChar = 0;
                }
            }

            keepCursorVisibleHorizontally();
            setDisable(false);
            showLoadingCircle(false);

            requestFocus();
            post(() -> {
                showKeyboard();
                requestFocus();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.restartInput(this);
            });
        };

        // In-memory mode (sourceFile == null): no window loads.
        if (isFileCleared || sourceFile == null ||
                (targetLine >= windowStartLine && targetLine < windowStartLine + linesWindow.size())) {
            completionAction.run();
        } else {
            int targetStart = Math.max(0, targetLine - prefetchLines);
            loadWindowAround(targetStart, completionAction);
        }
    }

    public void insertCharAtCursor(char c) {
        invalidatePendingIO();
        editVersion.incrementAndGet();

        if (isFileCleared) isFileCleared = false;

        // FIX: لو فيه تحديد، لازم يكون استبدال ذري (خصوصاً خارج الشاشة)
        if (hasSelection) {
            replaceSelectionWithText(String.valueOf(c));
            return;
        }

        if (hasComposing) { hasComposing = false; composingLength = 0; }

        ensureLineInWindow(cursorLine, true);
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            post(() -> insertCharAtCursor(c));
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        if (localIdx < 0 || localIdx >= linesWindow.size()) {
            synchronized (linesWindow) {
                if (linesWindow.isEmpty()) linesWindow.add("");
            }
            localIdx = Math.max(0, Math.min(localIdx, linesWindow.size() - 1));
        }

        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";

            if (c == '\n') {
                int oldLineCount = getLinesCount();
                String before = base.substring(0, Math.min(cursorChar, base.length()));
                String after = base.substring(Math.min(cursorChar, base.length()));
                Float oldWidth = lineWidthCache.get(cursorLine);

                updateLocalLine(localIdx, before);
                linesWindow.add(localIdx + 1, after);

                modifiedLines.put(cursorLine, before);
                modifiedLines.put(cursorLine + 1, after);

                computeWidthForLine(cursorLine, before);
                computeWidthForLine(cursorLine + 1, after);

                if (oldWidth != null && oldWidth >= currentMaxWindowLineWidth) recalculateMaxLineWidth();
                cursorLine++; cursorChar = 0;

                int newLineCount = getLinesCount();
                if (showLineNumbers && String.valueOf(oldLineCount).length() != String.valueOf(newLineCount).length()) {
                    requestLayout();
                }
            } else {
                int pos = Math.max(0, Math.min(cursorChar, base.length()));
                String modified = base.substring(0, pos) + c + base.substring(pos);
                updateLocalLine(localIdx, modified);
                modifiedLines.put(cursorLine, modified);
                cursorChar++;
                float newWidth = paint.measureText(modified);
                synchronized (lineWidthCache) { lineWidthCache.put(cursorLine, newWidth); }
                currentMaxWindowLineWidth = Math.max(currentMaxWindowLineWidth, newWidth);
                globalMaxLineWidth = Math.max(globalMaxLineWidth, currentMaxWindowLineWidth);
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
        editVersion.incrementAndGet();

        if (hasComposing) { deleteComposing(); return; }
        if (isFileCleared) {
            if (cursorLine == 0 && cursorChar > 0) {
                cursorChar = Math.max(0, cursorChar - 1);
                invalidate();
            }
            return;
        }

        ensureLineInWindow(cursorLine, true);
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            post(this::deleteCharAtCursor);
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        if (localIdx < 0 || localIdx >= linesWindow.size()) return;

        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";

            if (cursorChar > 0) {
                Float oldWidth = lineWidthCache.get(cursorLine);
                int safeStart = Math.max(0, cursorChar - 1);
                String modified = base.substring(0, safeStart) + base.substring(cursorChar);
                updateLocalLine(localIdx, modified);
                modifiedLines.put(cursorLine, modified);
                cursorChar = safeStart;
                computeWidthForLine(cursorLine, modified);
                if (oldWidth != null && oldWidth >= currentMaxWindowLineWidth) recalculateMaxLineWidth();
                invalidateLineGlobal(cursorLine);
            } else if (cursorLine > 0) {
                int oldLineCount = getLinesCount();
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

                recalculateMaxLineWidth();
                cursorLine = prevGlobal;
                cursorChar = prev.length();
                computeWidthForLine(prevGlobal, merged);

                int newLineCount = getLinesCount();
                if (showLineNumbers && String.valueOf(oldLineCount).length() != String.valueOf(newLineCount).length()) {
                    requestLayout();
                }
                invalidate();
            }
        }
    }

    public void deleteForwardAtCursor() {
        invalidatePendingIO();
        editVersion.incrementAndGet();

        if (hasComposing) { deleteComposing(); return; }
        if (isFileCleared) return;

        ensureLineInWindow(cursorLine, true);
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            post(this::deleteForwardAtCursor);
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if (base == null) base = "";

            if (cursorChar < base.length()) {
                Float oldWidth = lineWidthCache.get(cursorLine);
                String modified = base.substring(0, cursorChar) + base.substring(cursorChar + 1);
                updateLocalLine(localIdx, modified);
                modifiedLines.put(cursorLine, modified);
                computeWidthForLine(cursorLine, modified);
                if (oldWidth != null && oldWidth >= currentMaxWindowLineWidth) recalculateMaxLineWidth();
                invalidateLineGlobal(cursorLine);
            } else {
                int nextGlobal = cursorLine + 1;
                if (isEof && nextGlobal >= windowStartLine + linesWindow.size()) return;

                ensureLineInWindow(nextGlobal, true);
                int nextLocal = nextGlobal - windowStartLine;
                if (nextLocal >= 0 && nextLocal < linesWindow.size()) {
                    String next = getLineFromWindowLocal(nextLocal);
                    if (next == null) next = "";
                    String merged = base + next;
                    updateLocalLine(localIdx, merged);
                    linesWindow.remove(nextLocal);
                    modifiedLines.put(cursorLine, merged);
                    recalculateMaxLineWidth();
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
        editVersion.incrementAndGet();

        ensureLineInWindow(composingLine, true);
        if (isWindowLoading && (composingLine < windowStartLine || composingLine >= windowStartLine + linesWindow.size())) {
            post(() -> replaceComposingWith(textSeq));
            return;
        }
        int local = composingLine - windowStartLine;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(local);
            if (base == null) base = "";
            int start = Math.max(0, Math.min(composingOffset, base.length()));
            int end = Math.max(0, Math.min(composingOffset + composingLength, base.length()));
            String newLine = base.substring(0, start) + textSeq + base.substring(end);
            updateLocalLine(local, newLine);
            modifiedLines.put(composingLine, newLine);
            composingLength = textSeq.length();
            cursorLine = composingLine;
            cursorChar = composingOffset + composingLength;
            computeWidthForLine(composingLine, newLine);
            recalculateMaxLineWidth();
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

    private static final long COPY_CUT_MAX_LINES = 20000L;
    private static final int COPY_CUT_MAX_CHARS = 8_000_000; // safety cap

    public String getSelectedText() {
        if (!hasSelection) return null;
        if (shouldHideCopyCutForSelection()) return null;

        int sL = selStartLine, sC = selStartChar, eL = selEndLine, eC = selEndChar;
        if (comparePos(sL, sC, eL, eC) > 0) { int tL=sL,tC=sC; sL=eL; sC=eC; eL=tL; eC=tC; }
        return buildSelectedTextBlocking(sL, sC, eL, eC, COPY_CUT_MAX_CHARS);
    }

    public void copySelectionToClipboard() {
        copyOrCutSelection(false);
    }

    public void cutSelectionToClipboard() {
        copyOrCutSelection(true);
    }

    private void copyOrCutSelection(final boolean cut) {
        if (!hasSelection) return;

        // Hidden/disabled for huge selections (requested behavior)
        if (shouldHideCopyCutForSelection()) return;

        int sL = selStartLine, sC = selStartChar, eL = selEndLine, eC = selEndChar;
        if (comparePos(sL, sC, eL, eC) > 0) { int tL=sL,tC=sC; sL=eL; sC=eC; eL=tL; eC=tC; }

        long lines = (long) eL - (long) sL + 1L;
        if (lines > COPY_CUT_MAX_LINES) return;

        final int fsL = sL, fsC = sC, feL = eL, feC = eC;

        final Runnable showLater = () -> showLoadingCircle(true);
        postDelayed(showLater, 200);

        ioHandler.post(() -> {
            final String text = buildSelectedTextBlocking(fsL, fsC, feL, feC, COPY_CUT_MAX_CHARS);
            post(() -> {
                removeCallbacks(showLater);
                showLoadingCircle(false);

                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", (text == null) ? "" : text));

                if (cut) {
                    deleteSelection();
                }
            });
        });
    }

    private String buildSelectedTextBlocking(int sL, int sC, int eL, int eC, int maxChars) {
        if (comparePos(sL, sC, eL, eC) > 0) { int tL=sL,tC=sC; sL=eL; sC=eC; eL=tL; eC=tC; }

        // In-memory (no file backing): build from render-safe access
        if (sourceFile == null) {
            StringBuilder sb = new StringBuilder();
            for (int L = sL; L <= eL; L++) {
                String ln = getLineTextForRender(L);
                if (ln == null) ln = "";
                int startIdx = (L == sL) ? Math.min(sC, ln.length()) : 0;
                int endIdx = (L == eL) ? Math.min(eC, ln.length()) : ln.length();
                if (endIdx > startIdx) sb.append(ln, startIdx, endIdx);
                if (L < eL) sb.append('\n');

                if (sb.length() > maxChars) return sb.substring(0, maxChars);
            }
            return sb.toString();
        }

        // File-backed: sequential read from start line, overriding with modifiedLines when available
        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
            long startByte;
            if (isIndexReady) {
                synchronized (lineOffsetsLock) {
                    if (sL >= 0 && sL < lineOffsets.length) startByte = lineOffsets[sL];
                    else startByte = raf.length();
                }
            } else {
                startByte = findLineStartByteByScanning(raf, sL);
            }

            raf.seek(startByte);
            try (BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(raf.getFD()), StandardCharsets.UTF_8), 8192)) {

                StringBuilder sb = new StringBuilder();
                for (int L = sL; L <= eL; L++) {
                    String fileLine = br.readLine();
                    if (fileLine == null) fileLine = "";

                    String ln;
                    synchronized (modifiedLines) {
                        ln = modifiedLines.containsKey(L) ? modifiedLines.get(L) : fileLine;
                    }
                    if (ln == null) ln = "";

                    int startIdx = (L == sL) ? Math.min(sC, ln.length()) : 0;
                    int endIdx = (L == eL) ? Math.min(eC, ln.length()) : ln.length();
                    if (endIdx > startIdx) sb.append(ln, startIdx, endIdx);
                    if (L < eL) sb.append('\n');

                    if (sb.length() > maxChars) return sb.substring(0, maxChars);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void pasteFromClipboard() {
        invalidatePendingIO();
        editVersion.incrementAndGet();

        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence txt = cd.getItemAt(0).coerceToText(getContext());
        if (txt == null) return;
        insertTextAtCursor(txt.toString());
    }

    interface LineCountCallback { void onResult(int count); }

    private void countTotalLines(LineCountCallback callback) {
        final int taskVersion = ioTaskVersion.get();
        ioHandler.post(() -> {
            if (taskVersion != ioTaskVersion.get()) { post(() -> callback.onResult(-1)); return; }
            if (isIndexReady && sourceFile != null) {
                synchronized (lineOffsetsLock) {
                    post(() -> callback.onResult(lineOffsets.length));
                }
                return;
            }
            int count = 0;
            if (sourceFile != null && sourceFile.exists()) {
                try (FileInputStream is = new FileInputStream(sourceFile)) {
                    byte[] buffer = new byte[8192]; int len; boolean empty = true;
                    while ((len = is.read(buffer)) != -1) {
                        empty = false;
                        for (int i = 0; i < len; i++) if (buffer[i] == '\n') count++;
                    }
                    if (!empty) count++;
                } catch (Exception e) { count = -1; }
            }
            final int finalCount = count;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(finalCount));
        });
    }

    public void selectAll() {
        final boolean keyboardWasVisible = keyboardHeight > 0;
        setDisable(true);
        showLoadingCircle(true);

        isSelectAllActive = true;
        isEntireFileSelected = true;
        hasSelection = true;

        selStartLine = 0;
        selStartChar = 0;
        hidePopup();

        // =========================
        // In-memory mode (no file):
        // - Happens after "select all -> delete" (file cleared), then user types new text
        // - Also covers scenarios where content is edited but not persisted to disk
        // =========================
        if (sourceFile == null) {
            synchronized (linesWindow) {
                if (linesWindow.isEmpty()) linesWindow.add("");
                // With no file backing, treat current window as the whole document.
                if (windowStartLine != 0) windowStartLine = 0;
                isEof = true;
            }

            selEndLine = Math.max(0, windowStartLine + linesWindow.size() - 1);
            String lastLineText = getLineTextForRender(selEndLine);
            selEndChar = lastLineText.length();
            cursorLine = selEndLine;
            cursorChar = selEndChar;

            scrollY = Math.max(0, (selEndLine - 5) * lineHeight);
            clampScrollY();

            setDisable(false);
            showLoadingCircle(false);
            invalidate();
            requestFocus();
            showPopupAtSelection();

            post(() -> {
                requestFocus();
                if (keyboardWasVisible) showKeyboard();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.restartInput(PopEditText.this);
            });
            return;
        }

        if (isFileCleared) {
            selEndLine = 0; selEndChar = 0; cursorLine = 0; cursorChar = 0;
            setDisable(false); showLoadingCircle(false); invalidate(); showPopupAtSelection();
            if (keyboardWasVisible) showKeyboard();
            return;
        }

        // If we're already at EOF, we can select to the current visible logical end
        // without waiting for the index (important when user appended lines after EOF).
        if (isEof) {
            int windowLast = Math.max(0, windowStartLine + linesWindow.size() - 1);
            selEndLine = windowLast;
            String lastLineText = getLineTextForRender(windowLast);
            selEndChar = lastLineText.length();
            cursorLine = windowLast;
            cursorChar = selEndChar;

            scrollY = Math.max(0, (windowLast - 5) * lineHeight);
            clampScrollY();

            setDisable(false);
            showLoadingCircle(false);
            invalidate();
            requestFocus();
            showPopupAtSelection();

            post(() -> {
                requestFocus();
                if (keyboardWasVisible) showKeyboard();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.restartInput(PopEditText.this);
            });
            return;
        }

        // الأفضل: لو index جاهز نروح نهاية الملف بدقة (بدون قفزة غلط)
        Runnable goToEndUsingIndex = () -> {
            if (!isIndexReady || sourceFile == null) return;

            int fileLastLine;
            synchronized (lineOffsetsLock) {
                fileLastLine = Math.max(0, lineOffsets.length - 1);
            }

            // If the current window actually goes beyond file end (due to appended in-memory lines),
            // prefer the window end and DO NOT reload from file (reload would drop the appended lines).
            if (isEof) {
                int windowLast = Math.max(0, windowStartLine + linesWindow.size() - 1);
                if (windowLast > fileLastLine) {
                    selEndLine = windowLast;
                    String lastLineText = getLineTextForRender(windowLast);
                    selEndChar = lastLineText.length();
                    cursorLine = windowLast;
                    cursorChar = selEndChar;

                    scrollY = Math.max(0, (windowLast - 5) * lineHeight);
                    clampScrollY();

                    setDisable(false);
                    showLoadingCircle(false);
                    invalidate();
                    requestFocus();
                    showPopupAtSelection();

                    post(() -> {
                        requestFocus();
                        if (keyboardWasVisible) showKeyboard();
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.restartInput(PopEditText.this);
                    });
                    return;
                }
            }

            selEndLine = fileLastLine;

            int targetStart = Math.max(0, fileLastLine - prefetchLines);

            loadWindowAround(targetStart, () -> post(() -> {
                String lastLineText = getLineTextForRender(fileLastLine);
                selEndChar = lastLineText.length();
                cursorLine = fileLastLine;
                cursorChar = selEndChar;

                scrollY = Math.max(0, (fileLastLine - 5) * lineHeight);
                clampScrollY();

                setDisable(false);
                showLoadingCircle(false);
                invalidate();
                requestFocus();
                showPopupAtSelection();

                post(() -> {
                    requestFocus();
                    if (keyboardWasVisible) showKeyboard();
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.restartInput(PopEditText.this);
                });
            }));
        };

        if (isIndexReady) {
            goToEndUsingIndex.run();
            return;
        }

        // لو index مو جاهز: ابدأ بناءه ثم انتظر جاهزيته (بدل "قرب النهاية" الغلط)
        if (!isIndexBuilding) ioHandler.post(this::buildFileIndex);

        // نحدد selEndLine مؤقتاً للهايلايت بواسطة countTotalLines (سريع)
        countTotalLines(totalLines -> {
            selEndLine = Math.max(0, totalLines - 1);

            final int ticket = editVersion.incrementAndGet();
            Runnable poll = new Runnable() {
                @Override
                public void run() {
                    if (ticket != editVersion.get()) return;

                    // Important: if file became unavailable (e.g. cleared and switched to memory),
                    // stop waiting to avoid infinite spinner.
                    if (sourceFile == null) {
                        setDisable(false);
                        showLoadingCircle(false);
                        invalidate();
                        showPopupAtSelection();
                        if (keyboardWasVisible) showKeyboard();
                        return;
                    }

                    if (isIndexReady) {
                        goToEndUsingIndex.run();
                    } else {
                        mainHandler.postDelayed(this, 80);
                    }
                }
            };
            mainHandler.post(poll);
        });
    }

    // ==============================
    // DELETE/REPLACE SELECTION (FIXED)
    // ==============================
    public void deleteSelection() {
        replaceSelectionWithText("");
    }

    private static final class CursorTarget {
        final int line;
        final int ch;
        CursorTarget(int line, int ch) { this.line = line; this.ch = ch; }
    }

    private CursorTarget computeCursorAfterInsert(int baseLine, int baseChar, String insertText) {
        if (insertText == null) insertText = "";
        int newLines = 0;

        int lastNl = insertText.lastIndexOf('\n');
        if (lastNl >= 0) {
            for (int i = 0; i < insertText.length(); i++) {
                if (insertText.charAt(i) == '\n') newLines++;
            }
            int lastSegLen = insertText.length() - lastNl - 1;
            return new CursorTarget(baseLine + newLines, lastSegLen);
        }
        return new CursorTarget(baseLine, baseChar + insertText.length());
    }

    private void replaceSelectionWithText(String insertText) {
        invalidatePendingIO();
        final int opToken = editVersion.incrementAndGet();

        if (insertText == null) insertText = "";

        if (!hasSelection) {
            if (!insertText.isEmpty()) insertTextAtCursor(insertText);
            // No selection means no large edit UI was started for it.
            return;
        }

        // Normalize selection
        int sL = selStartLine, sC = selStartChar, eL = selEndLine, eC = selEndChar;
        if (comparePos(sL, sC, eL, eC) > 0) {
            int tL = sL, tC = sC; sL = eL; sC = eC; eL = tL; eC = tC;
        }

        final boolean selectAllLike = isSelectAllActive || isEntireFileSelected;
        beginLargeEditUiIfNeeded(true, sL, eL, selectAllLike);

        // This is the critical fix: The "Select All" path now correctly cleans up and finalizes the UI state.
        if (selectAllLike) {
            // Reset all data structures to represent an empty document.
            synchronized (linesWindow) {
                linesWindow.clear();
                linesWindow.add("");
                windowStartLine = 0;
                isEof = true;
            }
            synchronized (modifiedLines) { modifiedLines.clear(); }
            synchronized (lineWidthCache) { lineWidthCache.clear(); }
            currentMaxWindowLineWidth = 0f;
            globalMaxLineWidth = 0f;

            // Transition to in-memory mode, as the file is now gone.
            isFileCleared = true;
            sourceFile = null;
            synchronized (lineOffsetsLock) { lineOffsets = new long[0]; }
            isIndexReady = false;
            isIndexBuilding = false;

            // Reset cursor, selection, and scroll position.
            cursorLine = 0; cursorChar = 0;
            selStartLine = 0; selEndLine = 0;
            selStartChar = 0; selEndChar = 0;
            scrollY = 0; scrollX = 0;
            clearSelectionStateAfterDelete();

            // Perform insertion if replacing text.
            if (!insertText.isEmpty()) {
                String[] newLines = insertText.split("\n", -1);
                synchronized(linesWindow) {
                    linesWindow.set(0, newLines[0]);
                    for (int i = 1; i < newLines.length; i++) {
                        linesWindow.add(i, newLines[i]);
                    }
                }
                CursorTarget newPos = computeCursorAfterInsert(0, 0, insertText);
                cursorLine = newPos.line;
                cursorChar = newPos.ch;
            }

            // Crucially, end the large edit UI and force a redraw.
            endLargeEditUi(true);
            recalculateMaxLineWidth();
            keepCursorVisibleHorizontally();
            requestLayout(); // Request layout to update gutter width after content cleared
            return;
        }


        // same line + no '\n' => window-only fast path
        if (sL == eL && insertText.indexOf('\n') < 0) {
            ensureLineInWindow(sL, true);
            if (isWindowLoading && (sL < windowStartLine || sL >= windowStartLine + linesWindow.size())) {
                final String txtFinal = insertText;
                post(() -> replaceSelectionWithText(txtFinal));
                return;
            }

            int local = sL - windowStartLine;
            if (local >= 0 && local < linesWindow.size()) {
                synchronized (linesWindow) {
                    String line = getLineFromWindowLocal(local);
                    if (line == null) line = "";

                    int a = Math.max(0, Math.min(sC, line.length()));
                    int b = Math.max(0, Math.min(eC, line.length()));
                    if (b < a) { int t=a; a=b; b=t; }

                    String merged = line.substring(0, a) + insertText + line.substring(b);
                    updateLocalLine(local, merged);
                    modifiedLines.put(sL, merged);

                    cursorLine = sL;
                    cursorChar = a + insertText.length();

                    computeWidthForLine(sL, merged);
                    recalculateMaxLineWidth();
                }
            }

            clearSelectionStateAfterDelete();
            invalidate();
            keepCursorVisibleHorizontally();
            endLargeEditUi(false);
            return;
        }

        // multi-line or inserted text contains '\n'
        final CursorTarget target = computeCursorAfterInsert(sL, sC, insertText);

        // Optional immediate UI update if fully in window
        boolean fullyInWindow = (sL >= windowStartLine) && (eL < windowStartLine + linesWindow.size());
        if (fullyInWindow) {
            applyMultiLineReplaceInWindowNow(sL, sC, eL, eC, insertText, target);
        } else {
            cursorLine = sL;
            cursorChar = sC;
        }

        clearSelectionStateAfterDelete();
        keepCursorVisibleHorizontally(); // This scrolls to the new cursor and invalidates.
        endLargeEditUi(false);

        if (sourceFile == null || isFileCleared) {
            if (!insertText.isEmpty()) insertTextAtCursor(insertText);
            endLargeEditUi(false);
            return;
        }

        final File inFile = sourceFile;
        // ابدأ إعادة كتابة الملف في الخلفية بدون تعطيل الواجهة وبدون دائرة تحميل.
        rewriteReplaceRangeAsync(opToken, inFile, sL, sC, eL, eC, insertText, target);
    }

    private void applyMultiLineReplaceInWindowNow(int sL, int sC, int eL, int eC, String insertText, CursorTarget target) {
        synchronized (linesWindow) {
            int oldLineCount = getLinesCount();
            int sLocal = sL - windowStartLine;
            int eLocal = eL - windowStartLine;
            if (sLocal < 0 || eLocal < 0 || sLocal >= linesWindow.size() || eLocal >= linesWindow.size()) return;
            if (sLocal > eLocal) { int t=sLocal; sLocal=eLocal; eLocal=t; }

            String startLine = linesWindow.get(sLocal);
            String endLine = linesWindow.get(eLocal);
            if (startLine == null) startLine = "";
            if (endLine == null) endLine = "";

            int startIdx = Math.max(0, Math.min(sC, startLine.length()));
            int endIdx = Math.max(0, Math.min(eC, endLine.length()));

            String left = startLine.substring(0, startIdx);
            String right = endLine.substring(endIdx);

            String mergedText = left + (insertText == null ? "" : insertText) + right;
            String[] parts = mergedText.split("\n", -1);

            linesWindow.set(sLocal, parts[0]);
            if (eLocal >= sLocal + 1) {
                linesWindow.subList(sLocal + 1, eLocal + 1).clear();
            }

            if (parts.length > 1) {
                List<String> toInsert = new ArrayList<>(parts.length - 1);
                for (int i = 1; i < parts.length; i++) toInsert.add(parts[i]);
                linesWindow.addAll(sLocal + 1, toInsert);
            }

            cursorLine = Math.max(0, target.line);
            cursorChar = Math.max(0, target.ch);

            int newLineCount = getLinesCount();
            if (showLineNumbers && oldLineCount > 0 && String.valueOf(oldLineCount).length() != String.valueOf(newLineCount).length()) {
                requestLayout();
            }

            recalculateMaxLineWidth();
        }
    }

    private void rewriteReplaceRangeAsync(int opToken, File inFile,
                                         int sL, int sC, int eL, int eC,
                                         String insertText, CursorTarget target) {
        ioHandler.post(() -> {
            try {
                if (inFile == null || !inFile.exists()) {
                    post(() -> { /* no-op: UI not disabled */ });
                    return;
                }

                RangeBytes range = computeByteRangeFastOrScan(inFile, sL, sC, eL, eC);
                if (range == null) {
                    post(() -> { /* no-op: UI not disabled */ });
                    return;
                }

                File outFile = File.createTempFile("popedit_", ".tmp", getContext().getCacheDir());
                byte[] insertBytes = (insertText == null) ? new byte[0] : insertText.getBytes(StandardCharsets.UTF_8);

                try (RandomAccessFile rafIn = new RandomAccessFile(inFile, "r");
                     FileChannel inCh = rafIn.getChannel();
                     RandomAccessFile rafOut = new RandomAccessFile(outFile, "rw");
                     FileChannel outCh = rafOut.getChannel()) {

                    long fileLen = rafIn.length();
                    long startByte = Math.max(0, Math.min(range.startByte, fileLen));
                    long endByte = Math.max(0, Math.min(range.endByte, fileLen));
                    if (endByte < startByte) { long t = startByte; startByte = endByte; endByte = t; }

                    transferRange(inCh, outCh, 0, startByte);

                    if (insertBytes.length > 0) {
                        outCh.write(ByteBuffer.wrap(insertBytes));
                    }

                    transferRange(inCh, outCh, endByte, fileLen - endByte);
                    outCh.force(true);
                }

                post(() -> {
                    if (opToken != editVersion.get()) return;

                    invalidatePendingIO();

                    sourceFile = outFile;
                    isFileCleared = false;

                    synchronized (modifiedLines) { modifiedLines.clear(); }
                    synchronized (lineWidthCache) { lineWidthCache.clear(); }
                    currentMaxWindowLineWidth = 0f;
                    globalMaxLineWidth = 0f;

                    synchronized (lineOffsetsLock) { lineOffsets = new long[0]; }
                    isIndexReady = false;
                    isIndexBuilding = false;
                    isEof = false;

                    ioHandler.post(this::buildFileIndex);

                    cursorLine = Math.max(0, target.line);
                    cursorChar = Math.max(0, target.ch);

                    // لا تعمل "Reload" للنافذة بعد الحذف/الاستبدال إذا كانت النتيجة ضمن النافذة الحالية.
                    // هذا يمنع دائرة التحميل ويمنع القفز/الزمن الطويل مع الملفات الضخمة.
                    boolean cursorInsideWindow = (cursorLine >= windowStartLine && cursorLine < windowStartLine + linesWindow.size());

                    if (cursorInsideWindow) {
                        // النافذة الحالية تم تعديلها مسبقاً (fast path)، فقط أعد حساب العرض وحدث الرسم.
                        recalculateMaxLineWidth();
                        requestFocus();
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.restartInput(this);
                        invalidate();
                    } else {
                        int targetStart = Math.max(0, cursorLine - prefetchLines);
                        loadWindowAround(targetStart, () -> {
                            String ln = getLineTextForRender(cursorLine);
                            cursorChar = Math.min(cursorChar, ln.length());
                            clampScrollY();
                            keepCursorVisibleHorizontally();
                            requestFocus();
                            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.restartInput(this);
                            invalidate();
                        });
                    }
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                post(() -> { /* no-op: UI not disabled */ });
            }
        });
    }

    private RangeBytes computeByteRangeFastOrScan(File file, int sL, int sC, int eL, int eC) {
        if (comparePos(sL, sC, eL, eC) > 0) {
            int tl=sL, tc=sC; sL=eL; sC=eC; eL=tl; eC=tc;
        }

        if (isIndexReady && file != null) {
            RangeBytes fast = computeByteRangeUsingIndex(file, sL, sC, eL, eC);
            if (fast != null) return fast;
        }

        return computeByteRangeByScanning(file, sL, sC, eL, eC);
    }

    private RangeBytes computeByteRangeUsingIndex(File file, int sL, int sC, int eL, int eC) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long startLineByte, endLineByte;
            synchronized (lineOffsetsLock) {
                if (!isIndexReady) return null;
                if (sL < 0 || eL < 0) return null;
                if (sL >= lineOffsets.length || eL >= lineOffsets.length) return null;
                startLineByte = lineOffsets[sL];
                endLineByte = lineOffsets[eL];
            }

            String startLineText = readLineUtf8AtByte(raf, startLineByte);
            String endLineText = (eL == sL) ? startLineText : readLineUtf8AtByte(raf, endLineByte);

            long startByte = startLineByte + computeByteOffsetInLineUtf8(startLineText, sC);
            long endByte = endLineByte + computeByteOffsetInLineUtf8(endLineText, eC);

            return new RangeBytes(startByte, endByte);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void applyMultiLineDeleteInWindowNow(int sL, int sC, int eL, int eC) {
        synchronized (linesWindow) {
            int sLocal = sL - windowStartLine;
            int eLocal = eL - windowStartLine;
            if (sLocal < 0 || eLocal >= linesWindow.size() || sLocal > eLocal) return;

            String startLine = linesWindow.get(sLocal);
            String endLine = linesWindow.get(eLocal);
            if (startLine == null) startLine = "";
            if (endLine == null) endLine = "";

            int startIdx = Math.max(0, Math.min(sC, startLine.length()));
            int endIdx = Math.max(0, Math.min(eC, endLine.length()));

            String left = startLine.substring(0, startIdx);
            String right = endLine.substring(endIdx);

            String merged = left + right;

            linesWindow.set(sLocal, merged);
            if (eLocal > sLocal) {
                linesWindow.subList(sLocal + 1, eLocal + 1).clear();
            }

            modifiedLines.put(windowStartLine + sLocal, merged);
            for (int i = sLocal + 1; i < linesWindow.size(); i++) {
                modifiedLines.put(windowStartLine + i, linesWindow.get(i));
            }

            cursorLine = sL;
            cursorChar = left.length();

            recalculateMaxLineWidth();
        }
    }

    private void clearSelectionStateAfterDelete() {
        hasSelection = false;
        selecting = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        hidePopup();
        resetCursorBlink();
    }

    private static final class RangeBytes {
        final long startByte, endByte;
        RangeBytes(long s, long e) { startByte = s; endByte = e; }
    }

    private void transferRange(FileChannel inCh, FileChannel outCh, long position, long count) throws Exception {
        long remaining = count;
        long pos = position;
        while (remaining > 0) {
            long sent = inCh.transferTo(pos, remaining, outCh);
            if (sent <= 0) break;
            pos += sent;
            remaining -= sent;
        }
    }

    private RangeBytes computeByteRangeByScanning(File file, int sL, int sC, int eL, int eC) {
        if (comparePos(sL, sC, eL, eC) > 0) {
            int tl=sL, tc=sC; sL=eL; sC=eC; eL=tl; eC=tc;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long[] starts = findTwoLineStartBytesByScanning(raf, sL, eL);
            long startLineByte = starts[0];
            long endLineByte = starts[1];

            String startLineText = readLineUtf8AtByte(raf, startLineByte);
            String endLineText = (eL == sL) ? startLineText : readLineUtf8AtByte(raf, endLineByte);

            long startByte = startLineByte + computeByteOffsetInLineUtf8(startLineText, sC);
            long endByte = endLineByte + computeByteOffsetInLineUtf8(endLineText, eC);

            return new RangeBytes(startByte, endByte);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fallback helper used when the line index is not ready.
     * Returns the byte offset at which the given 0-based line starts.
     * This scans the file sequentially (O(n)) so it should only be used
     * for occasional operations like copy/cut when index isn't available.
     */
    private long findLineStartByteByScanning(RandomAccessFile raf, int targetLine) throws Exception {
        if (targetLine <= 0) return 0L;
        long[] starts = findTwoLineStartBytesByScanning(raf, targetLine, targetLine);
        return (starts != null && starts.length > 0) ? starts[0] : 0L;
    }

    private long[] findTwoLineStartBytesByScanning(RandomAccessFile raf, int lineA, int lineB) throws Exception {
        if (lineA < 0) lineA = 0;
        if (lineB < 0) lineB = 0;

        int a = Math.min(lineA, lineB);
        int b = Math.max(lineA, lineB);

        long offA = (a == 0) ? 0L : -1L;
        long offB = (b == 0) ? 0L : -1L;

        raf.seek(0);
        byte[] buf = new byte[8192];
        long pos = 0;
        int line = 0;

        while (true) {
            int n = raf.read(buf);
            if (n <= 0) break;

            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    line++;
                    long nextLineStart = pos + i + 1;

                    if (line == a && offA < 0) offA = nextLineStart;
                    if (line == b && offB < 0) offB = nextLineStart;

                    if (offA >= 0 && offB >= 0) {
                        if (lineA <= lineB) return new long[]{offA, offB};
                        return new long[]{offB, offA};
                    }
                }
            }
            pos += n;
        }

        long len = raf.length();
        if (offA < 0) offA = len;
        if (offB < 0) offB = len;

        if (lineA <= lineB) return new long[]{offA, offB};
        return new long[]{offB, offA};
    }

    private String readLineUtf8AtByte(RandomAccessFile raf, long byteOffset) throws Exception {
        raf.seek(byteOffset);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        byte[] buf = new byte[1024];
        boolean seenAny = false;

        while (true) {
            int n = raf.read(buf);
            if (n <= 0) break;

            int stop = -1;
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') { stop = i; break; }
            }

            if (stop >= 0) {
                seenAny = true;
                if (stop > 0 && buf[stop - 1] == '\r') {
                    baos.write(buf, 0, stop - 1);
                } else {
                    baos.write(buf, 0, stop);
                }
                break;
            } else {
                seenAny = true;
                baos.write(buf, 0, n);
            }

            if (baos.size() > 2_000_000) break;
        }

        if (!seenAny) return "";
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private long computeByteOffsetInLineUtf8(String lineText, int charIndex) {
        if (lineText == null) return 0L;
        int safe = Math.max(0, Math.min(charIndex, lineText.length()));
        if (safe == 0) return 0L;
        return lineText.substring(0, safe).getBytes(StandardCharsets.UTF_8).length;
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
        editVersion.incrementAndGet();

        if (text == null) return;
        if (text.isEmpty() && !hasSelection) return;

        // FIX: لو فيه تحديد، لازم يكون replace ذري
        if (hasSelection) {
            replaceSelectionWithText(text);
            return;
        }

        if (hasComposing) { hasComposing = false; composingLength = 0; }

        if (isFileCleared) isFileCleared = false;
        if (text.isEmpty()) { invalidate(); return; }

        String[] parts = text.split("\n", -1);
        ensureLineInWindow(cursorLine, true);
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            post(() -> insertTextAtCursor(text));
            return;
        }

        int local = cursorLine - windowStartLine;
        if (local < 0 || local >= linesWindow.size()) {
            synchronized (linesWindow) {
                if (linesWindow.isEmpty()) { linesWindow.add(""); local = 0; }
                else local = Math.max(0, Math.min(local, linesWindow.size() - 1));
            }
        }

        synchronized (linesWindow) {
            int oldLineCount = getLinesCount();
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
                for (int p = 1; p < parts.length - 1; p++) linesToInsert.add(parts[p]);

                String lastPart = parts[parts.length - 1];
                linesToInsert.add(lastPart + right);

                if (!linesToInsert.isEmpty()) linesWindow.addAll(local + 1, linesToInsert);
                for (int i = 0; i < linesToInsert.size(); i++) {
                    modifiedLines.put(cursorLine + 1 + i, linesToInsert.get(i));
                }

                cursorLine += (parts.length - 1);
                cursorChar = lastPart.length();
            }

            int newLineCount = getLinesCount();
            if (showLineNumbers && oldLineCount > 0 && String.valueOf(oldLineCount).length() != String.valueOf(newLineCount).length()) {
                requestLayout();
            }

            recalculateMaxLineWidth();
            keepCursorVisibleHorizontally();
            resetCursorBlink();
            invalidate();
        }
    }

    private void ensureLineInWindow(int globalLine, boolean blockingIfAbsent) {
        if (globalLine >= windowStartLine && globalLine < windowStartLine + linesWindow.size()) return;
        if (sourceFile != null) {
            int targetStart = Math.max(0, globalLine - prefetchLines);
            loadWindowAround(targetStart, null);
        }
    }

    private BufferedReader reopenReaderAtStart() {
        try {
            if (readerForFile != null) {
                try { readerForFile.close(); } catch (Exception ignored) {}
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

    private void computeWidthForLine(int globalIndex, String line) {
        float w = paint.measureText(line == null ? "" : line);
        synchronized (lineWidthCache) { lineWidthCache.put(globalIndex, w); }
    }

    private float getWidthForLine(int globalIndex, String line) {
        synchronized (lineWidthCache) {
            Float v = lineWidthCache.get(globalIndex);
            if (v != null) return v;
        }
        float w = paint.measureText(line == null ? "" : line);
        synchronized (lineWidthCache) { lineWidthCache.put(globalIndex, w); }
        return w;
    }

    private void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return !isDisabled;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (isDisabled) return null;
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new BaseInputConnection(this, true) {
            @Override public Editable getEditable() { return imeEditable; }

            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                if (isDisabled) return true;
                if (text == null) return super.commitText(text, newCursorPosition);

                if (hasSelection) {
                    replaceSelectionWithText(text.toString());
                    commitComposing(true);
                    return true;
                }

                if (hasComposing) {
                    replaceComposingWith(text);
                    commitComposing(true);
                    return true;
                }

                insertTextAtCursor(text.toString());
                commitComposing(true);
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (isDisabled) return true;
                if (text == null) return true;

                if (hasSelection) {
                    replaceSelectionWithText(text.toString());
                    return true;
                }

                ensureLineInWindow(cursorLine, true);
                if (!hasComposing) {
                    composingLine = cursorLine; composingOffset = cursorChar; composingLength = 0; hasComposing = true;
                }
                replaceComposingWith(text);
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (isDisabled) return true;

                if (hasSelection) {
                    replaceSelectionWithText("");
                    return true;
                }
                for (int i = 0; i < beforeLength; i++) deleteCharAtCursor();
                for (int i = 0; i < afterLength; i++) deleteForwardAtCursor();
                return true;
            }
        };
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isDisabled) return true;

        if (isZoomEnabled) {
            scaleGestureDetector.onTouchEvent(event);
            if (scaleGestureDetector.isInProgress()) {
                // If a scale gesture is in progress, consume the event
                return true;
            }
        }

        float ex = event.getX(), ey = event.getY();
        lastTouchX = ex;
        lastTouchY = ey;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetCursorBlink();
                if (!isFocused()) requestFocus();
                pointerDown = true; downX = ex; downY = ey; movedSinceDown = false;

                if (showPopup && (btnCopyRect.contains(ex, ey) || btnCutRect.contains(ex, ey) ||
                        btnPasteRect.contains(ex, ey) || btnDeleteRect.contains(ex, ey) || btnSelectAllRect.contains(ex, ey))) return true;

                if (!scroller.isFinished()) {
                    scroller.computeScrollOffset();
                    scrollX = scroller.getCurrX();
                    scrollY = scroller.getCurrY();
                    scroller.abortAnimation();
                }

                // FIX: Use getTextStartX() to correctly calculate touch coordinates relative to the text area.
                float gx = ex + scrollX - getTextStartX();
                float gy = ey + scrollY;
                if (hasSelection && leftHandleRect.contains(gx, gy)) { draggingHandle = 1; return true; }
                else if (hasSelection && rightHandleRect.contains(gx, gy)) { draggingHandle = 2; return true; }
                else if (isFocused() && !hasSelection && cursorHandleRect.contains(gx, gy)) { draggingHandle = 3; return true; }

                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ex - downX) > touchSlop || Math.abs(ey - downY) > touchSlop) movedSinceDown = true;

                if (draggingHandle != 0) {
                    updateHandlePosition(ex, ey);
                    if (draggingHandle == 1 || draggingHandle == 2) showPopupAtSelection();

                    float scrollMargin = lineHeight * 2, scrollSpeed = 25f;
                    autoScrollY = 0; autoScrollX = 0;
                    if (ey < scrollMargin) autoScrollY = -scrollSpeed;
                    else if (ey > (getHeight() - keyboardHeight) - scrollMargin) autoScrollY = scrollSpeed;
                    if (ex < scrollMargin) autoScrollX = -scrollSpeed;
                    else if (ex > getWidth() - scrollMargin) autoScrollX = scrollSpeed;

                    if (autoScrollX != 0 || autoScrollY != 0) mainHandler.post(autoScrollRunnable);
                    else mainHandler.removeCallbacks(autoScrollRunnable);

                    invalidate();
                    return true;
                }

                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_UP:
                mainHandler.removeCallbacks(autoScrollRunnable);
                pointerDown = false;

                if (showPopup) {
                    if (btnCopyRect.contains(ex, ey)) { copySelectionToClipboard(); hasSelection = false; isSelectAllActive = false; hidePopup(); invalidate(); return true; }
                    else if (btnCutRect.contains(ex, ey)) { cutSelectionToClipboard(); return true; }
                    else if (btnPasteRect.contains(ex, ey)) { pasteFromClipboard(); return true; }
                    else if (btnDeleteRect.contains(ex, ey)) { deleteSelection(); return true; }
                    else if (btnSelectAllRect.contains(ex, ey)) { if (!isSelectAllActive) selectAll(); else hidePopup(); return true; }
                }

                if (draggingHandle != 0) {
                    if (draggingHandle == 1 || draggingHandle == 2) showPopupAtSelection();
                    draggingHandle = 0; invalidate(); return true;
                }

                if (movedSinceDown && scroller.isFinished() && hasSelection) showPopupAtSelection();
                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(autoScrollRunnable);
                pointerDown = false; draggingHandle = 0; selecting = false;
                gestureDetector.onTouchEvent(event);
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void updateHandlePosition(float touchX, float touchY) {
        // FIX: Any manual adjustment of the selection handles must deactivate ALL "Select All" flags.
        // This prevents the editor from deleting all content when the user has reduced the selection.
        if (isSelectAllActive || isEntireFileSelected) {
            isSelectAllActive = false;
            isEntireFileSelected = false;
            // The popup needs to be redrawn as "Copy" and "Cut" might become available again.
            showPopupAtSelection();
        }

        // Correctly calculate X coordinate relative to the text area, accounting for the gutter.
        float x = touchX + scrollX - getTextStartX();
        float y = touchY + scrollY;

        int line = Math.max(0, (int) (y / lineHeight));

        if (isEof) {
            int lastValidLine = windowStartLine + linesWindow.size() - 1;
            if (line > lastValidLine) line = lastValidLine;
        }

        ensureLineInWindow(line, true);
        String ln = getLineTextForRender(line);
        int charIndex = getCharIndexForX(ln, x);

        if (draggingHandle == 1) {
            selStartLine = line; selStartChar = Math.max(0, Math.min(charIndex, ln.length()));
        } else if (draggingHandle == 2) {
            selEndLine = line; selEndChar = Math.max(0, Math.min(charIndex, ln.length()));
        } else if (draggingHandle == 3) {
            cursorLine = line; cursorChar = Math.max(0, Math.min(charIndex, ln.length()));
            keepCursorVisibleHorizontally();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDisabled) return true;

        if (hasSelection && event.isPrintingKey()) {
            int uc = event.getUnicodeChar();
            if (uc != 0) {
                replaceSelectionWithText(String.valueOf((char) uc));
            } else {
                replaceSelectionWithText("");
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: moveCursorLeft(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveCursorRight(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: moveCursorUp(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveCursorDown(); return true;

            case KeyEvent.KEYCODE_DEL:
                if (hasSelection) replaceSelectionWithText("");
                else deleteCharAtCursor();
                return true;

            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (hasSelection) replaceSelectionWithText("");
                else deleteForwardAtCursor();
                return true;

            case KeyEvent.KEYCODE_ENTER:
                if (hasSelection) replaceSelectionWithText("\n");
                else insertNewlineAtCursor();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void moveCursorLeft() {
        if (hasSelection) {
            int sL = selStartLine, sC = selStartChar;
            if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) > 0) {
                sL = selEndLine; sC = selEndChar;
            }
            cursorLine = sL; cursorChar = sC;
        } else if (cursorChar > 0) cursorChar--;
        else if (cursorLine > 0) {
            cursorLine--;
            String ln = getLineTextForRender(cursorLine);
            cursorChar = ln.length();
        }
        hasSelection = false; isSelectAllActive = false; isEntireFileSelected = false; hidePopup();
        resetCursorBlink();
        invalidate(); keepCursorVisibleHorizontally();
    }

    private void moveCursorRight() {
        if (hasSelection) {
            int eL = selEndLine, eC = selEndChar;
            if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) > 0) {
                eL = selStartLine; eC = selStartChar;
            }
            cursorLine = eL; cursorChar = eC;
        } else {
            String ln = getLineTextForRender(cursorLine);
            if (cursorChar < ln.length()) cursorChar++;
            else {
                int next = cursorLine + 1;
                if (!isEof || next < windowStartLine + linesWindow.size()) {
                    cursorLine = next; cursorChar = 0;
                }
            }
        }
        hasSelection = false; isSelectAllActive = false; isEntireFileSelected = false; hidePopup();
        resetCursorBlink();
        invalidate(); keepCursorVisibleHorizontally();
    }

    private void moveCursorUp() {
        if (hasSelection) {
            int sL = selStartLine, sC = selStartChar;
            if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) > 0) {
                sL = selEndLine; sC = selEndChar;
            }
            cursorLine = sL; cursorChar = sC;
        }
        if (cursorLine > 0) {
            cursorLine--;
            String ln = getLineTextForRender(cursorLine);
            cursorChar = Math.min(cursorChar, ln.length());
        }
        hasSelection = false; isSelectAllActive = false; isEntireFileSelected = false; hidePopup();
        resetCursorBlink();
        invalidate(); keepCursorVisibleHorizontally();
    }

    private void moveCursorDown() {
        if (hasSelection) {
            int eL = selEndLine, eC = selEndChar;
            if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) > 0) {
                eL = selStartLine; eC = selStartChar;
            }
            cursorLine = eL; cursorChar = eC;
        }
        int next = cursorLine + 1;
        if (!isEof || next < windowStartLine + linesWindow.size()) {
            cursorLine = next;
            String ln = getLineTextForRender(cursorLine);
            cursorChar = Math.min(cursorChar, ln.length());
        }
        hasSelection = false; isSelectAllActive = false; isEntireFileSelected = false; hidePopup();
        resetCursorBlink();
        invalidate(); keepCursorVisibleHorizontally();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (focused) {
            if (imm != null) imm.restartInput(this);
            resetCursorBlink();
        } else {
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
            mainHandler.removeCallbacks(blinkRunnable);
            isCursorVisible = true; // Make sure it's visible when not focused
            hasComposing = false; hasSelection = false; hidePopup();
        }
    }

    private void invalidateLineGlobal(int globalLine) {
        float top = (globalLine * lineHeight) - scrollY;
        invalidate(0, (int) Math.floor(top), getWidth(), (int) Math.ceil(top + lineHeight));
    }

    public int getLinesCount() {
        if (isIndexReady && lineOffsets.length > 0) return lineOffsets.length;
        if (isEof) return windowStartLine + linesWindow.size();
        if (!linesWindow.isEmpty()) return windowStartLine + linesWindow.size();
        return -1;
    }

    private void clampScrollX() {
        float max = Math.max(0f, getMaxLineWidthInWindow() - (getWidth() - getTextStartX()));
        if (scrollX < 0) scrollX = 0;
        if (scrollX > max) scrollX = max;
    }

    private void recalculateMaxLineWidth() {
        float mx = 0f;
        synchronized (linesWindow) {
            for (int i = 0; i < linesWindow.size(); i++) {
                mx = Math.max(mx, getWidthForLine(windowStartLine + i, linesWindow.get(i)));
            }
        }
        currentMaxWindowLineWidth = mx;
        globalMaxLineWidth = Math.max(globalMaxLineWidth, currentMaxWindowLineWidth);
    }

    private float getMaxLineWidthInWindow() {
        // This is the core fix for horizontal scrolling. The scroll range must be based on the
        // longest line seen anywhere in the file, not just the current visible window.
        return globalMaxLineWidth;
    }

    private void keepCursorVisibleHorizontally() {
        float cursorYTop = cursorLine * lineHeight;
        float cursorYBottom = cursorYTop + lineHeight;
        int viewHeight = getHeight() - keyboardHeight;
        if (viewHeight <= 0) viewHeight = getHeight();

        float visibleTop = scrollY;
        float visibleBottom = scrollY + viewHeight;

        if (isEof) {
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : MIN_BOTTOM_VISIBLE_SPACE;
            if (cursorYBottom > visibleBottom - paddingToUse) scrollY = cursorYBottom - (viewHeight - paddingToUse);
            else if (cursorYTop < visibleTop) scrollY = cursorYTop;
        } else {
            if (cursorYBottom > visibleBottom) scrollY = cursorYBottom - viewHeight;
            else if (cursorYTop < visibleTop) scrollY = cursorYTop;
        }

        if (keyboardHeight > 0) {
            float keyboardTop = getHeight() - keyboardHeight;
            // Keep caret/handles above the keyboard by ~3 lines (EditText-like behavior)
            float paddingAboveKeyboard = Math.min(lineHeight * 3f, Math.max(0f, keyboardHeight - lineHeight));
            float currentCursorViewY = cursorYBottom - scrollY;
            if (currentCursorViewY >= keyboardTop - paddingAboveKeyboard) {
                scrollY = cursorYBottom - (getHeight() - keyboardHeight - paddingAboveKeyboard);
            }
        }
        clampScrollY();

        String line = getLineTextForRender(cursorLine);
        String lineUntilCursor = line.substring(0, Math.min(cursorChar, line.length()));
        float cursorX = paint.measureText(lineUntilCursor);

        float visibleWidth = getWidth() - paddingLeft;
        float scrollMargin = 50f;
        if (cursorX < scrollX + scrollMargin) scrollX = Math.max(0, cursorX - scrollMargin);
        else if (cursorX > scrollX + visibleWidth - scrollMargin) scrollX = cursorX - visibleWidth + scrollMargin;

        clampScrollX();
        invalidate();
    }

    
private void populateDirectLinesForRange(int startLine, int endLineInclusive, java.util.Map<Integer, String> out) {
    if (out == null) return;
    if (sourceFile == null || !sourceFile.exists()) return;
    if (!isIndexReady) return;

    int start = Math.max(0, startLine);
    int end = Math.max(start, endLineInclusive);

    int maxLine = -1;
    synchronized (lineOffsetsLock) {
        maxLine = lineOffsets.length - 1;
    }
    if (maxLine < 0) return;
    if (start > maxLine) return;
    if (end > maxLine) end = maxLine;

    // Hard cap to keep drawing fast during fling.
    int cap = 220; // ~ couple screens worth
    if ((end - start + 1) > cap) end = start + cap - 1;

    // If cached, fill quickly first.
    synchronized (directLineCache) {
        for (int l = start; l <= end; l++) {
            String c = directLineCache.get(l);
            if (c != null) out.put(l, c);
        }
    }

    // Read missing contiguous segments in one go.
    int l = start;
    while (l <= end) {
        if (out.containsKey(l)) { l++; continue; }

        int segStart = l;
        int segEnd = l;
        while (segEnd + 1 <= end && !out.containsKey(segEnd + 1)) segEnd++;

        long off;
        synchronized (lineOffsetsLock) {
            if (segStart >= lineOffsets.length) break;
            off = lineOffsets[segStart];
        }

        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
            raf.seek(off);
            BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(raf.getFD()), StandardCharsets.UTF_8),
                    8192
            );
            for (int cur = segStart; cur <= segEnd; cur++) {
                String ln = br.readLine();
                if (ln == null) break;
                out.put(cur, ln);
            }
        } catch (Exception ignored) {
            // ignore: fallback to blank
        }

        l = segEnd + 1;
    }

    // Update cache
    synchronized (directLineCache) {
        for (java.util.Map.Entry<Integer, String> e : out.entrySet()) {
            if (e.getKey() >= start && e.getKey() <= end) {
                directLineCache.put(e.getKey(), (e.getValue() == null) ? "" : e.getValue());
            }
        }
    }
}

private String getLineTextForRenderWithDirect(int line, @Nullable java.util.Map<Integer, String> direct) {
    if (line < 0) return "";

    // Window first
    if (line >= windowStartLine && line < windowStartLine + linesWindow.size()) {
        String text = getLineFromWindowLocal(line - windowStartLine);
        return (text != null) ? text : "";
    }

    // Modified lines (recent edits)
    String mod = modifiedLines.get(line);
    if (mod != null) return mod;

    // Direct batch (during fast fling)
    if (direct != null) {
        String d = direct.get(line);
        if (d != null) return d;
    }

    // Cache
    synchronized (directLineCache) {
        String c = directLineCache.get(line);
        if (c != null) return c;
    }

    return "";
}


// Render-safe line getter (NO file random read here)
    private String getLineTextForRender(int line) {
        if (line < 0) return "";
        if (line >= windowStartLine && line < windowStartLine + linesWindow.size()) {
            String text = getLineFromWindowLocal(line - windowStartLine);
            return (text != null) ? text : "";
        }
        String mod = modifiedLines.get(line);
        return (mod != null) ? mod : "";
    }

    private long[] buildIndexJava(String filepath) {
        int numNewlines = 0;
        long fileLength = 0;

        try (RandomAccessFile raf = new RandomAccessFile(filepath, "r")) {
            fileLength = raf.length();
            if (fileLength == 0) {
                return new long[0]; // Empty file has no lines
            }

            byte[] buffer = new byte[8192];
            long currentReadPos = 0;
            while (currentReadPos < fileLength) {
                raf.seek(currentReadPos);
                int bytesRead = raf.read(buffer);
                if (bytesRead == -1) break; // EOF

                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == '\n') {
                        numNewlines++;
                    }
                }
                currentReadPos += bytesRead;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null; // Return null on error
        }

        // The number of lines is (number of newlines) + 1.
        // So the array size needs to be numNewlines + 1.
        long[] offsetsArray = new long[numNewlines + 1];
        int currentOffsetIndex = 0;

        try (RandomAccessFile raf = new RandomAccessFile(filepath, "r")) {
            offsetsArray[currentOffsetIndex++] = 0L; // First line starts at offset 0
            long currentPos = 0;
            byte[] buffer = new byte[8192];
            while (currentPos < fileLength) {
                raf.seek(currentPos);
                int bytesRead = raf.read(buffer);
                if (bytesRead == -1) break; // EOF

                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == '\n') {
                        // Store the offset of the character *after* the newline
                        if (currentOffsetIndex < offsetsArray.length) {
                            offsetsArray[currentOffsetIndex++] = currentPos + i + 1;
                        } else {
                            // This should not happen if the first pass line counting is correct.
                            // But as a safeguard, if we somehow counted more newlines than array size, break.
                            break;
                        }
                    }
                }
                currentPos += bytesRead;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null; // Return null on error
        }

        return offsetsArray;
    }

    private void cancelAndCloseReader() {
        ioHandler.post(() -> {
            try {
                if (readerForFile != null) { readerForFile.close(); readerForFile = null; }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void resetCursorBlink() {
        mainHandler.removeCallbacks(blinkRunnable);
        isCursorVisible = true;
        if (isFocused() && !hasSelection) {
            invalidate(); // Ensure it's drawn immediately
            mainHandler.postDelayed(blinkRunnable, 500);
        }
    }

    public void release() {
        cancelAndCloseReader();
        ioThread.quitSafely();
    }
}