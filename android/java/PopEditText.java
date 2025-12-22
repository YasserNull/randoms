package com.yn.tests.popedittext;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface; // Added for Typeface
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.util.AttributeSet;
import android.util.Log;
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
import java.util.Collections; // Added for Collections.sort
import java.util.SortedSet; // For Draw logic
import java.util.TreeSet; // For Draw logic
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher; // Added for Matcher
import java.util.regex.Pattern; // Added for Pattern

public class PopEditText extends View {

    public static final int STYLE_NORMAL = 0;
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_BOLD_ITALIC = 3;

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
    private boolean mJustFinishedScale = false;
    private boolean isScaling = false;
    private float lastFocusX, lastFocusY;
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
    private boolean isLineNumberSelecting = false;
    private int lineNumberSelectAnchorLine = -1;

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

    // typed-character fade animation (in-text, while drawing)
    private boolean isCharAnimationEnabled = true;
    private int charAnimationDurationMs = 200;
    @Nullable private String lastComposingTextForCharAnim;
    private int charAnimLine = -1;
    private int charAnimStartChar = 0;
    private int charAnimEndChar = 0;
    private float charAnimAlpha = 0f;
    @Nullable private ValueAnimator charAnimAnimator;
    private final Paint charAnimTmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // deleted-character fade animation (ghost draw after removal)
    private int delAnimLine = -1;
    private int delAnimAtChar = 0;
    @Nullable private String delAnimText;
    @Nullable private Paint delAnimPaint;
    private float delAnimAlpha = 0f;
    @Nullable private ValueAnimator delAnimAnimator;

    // floating popup (custom)
    private boolean showPopup = false;
    private boolean isMinimalPopup = false;
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
    private boolean isBracketMatchingEnabled = true;
    private final Paint bracketMatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float bracketMatchStrokeWidth = 2f;
    private final RectF bracketMatchRect = new RectF();
    @Nullable private BracketMatch cachedBracketMatch = null;
    private int cachedBracketMatchCursorLine = -1;
    private int cachedBracketMatchCursorChar = -1;
    private int cachedBracketMatchEditVersion = -1;
    private boolean isBracketGuidesEnabled = false;
    private final Paint bracketGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float bracketGuideStrokeWidth = 2f;
    private boolean isWhitespaceGuidesEnabled = false;
    private final Paint whitespaceGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float whitespaceGuideSpaceWidth = 0f;
    private float whitespaceGuideTabWidth = 0f;
    private float[] whitespaceWidthBuffer;
    private float[] measureWidthBuffer;
    private int whitespaceGuideSpaceStep = 1;
    private static final int DEFAULT_TAB_SIZE_SPACES = 4;
    private static final class WhitespaceDrawState {
        int syntaxIndex;
    }
    private final WhitespaceDrawState whitespaceDrawState = new WhitespaceDrawState();
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loadingCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF loadingCircleRect = new RectF();
    private final char[] lineNumberChars = new char[16];
    private final java.util.HashMap<Integer, String> directLinesTmp = new java.util.HashMap<>();
    private final Path teardropPath = new Path();
    private final Paint popupBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foldMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float foldMarkerGutterWidth = 0f;
    private float foldMarkerTextScale = 1f;
    private float foldMarkerSpacing = 8f;
    private float foldMarkerEdgePadding = 6f;

    // selection drawing (rounded)
    private final RectF selectionRectTmp = new RectF();
    private final Path selectionPathTmp = new Path();
    private final float[] selectionRadiiTmp = new float[8];
    private final RectF foldPlaceholderRect = new RectF();

    // handle dragging edge flags (to prevent horizontal autoscroll beyond line bounds)
    private boolean lastDragAtLineStart = false;
    private boolean lastDragAtLineEnd = false;

    // Drawing base to avoid float precision issues on very large line indices.
    // During onDraw, we render everything relative to the first visible line.
    private int drawBaseLine = 0;

    private float getDrawLineTop(int globalLine) {
        int drawIndex = globalLine;
        if (isCodeFoldingEnabled) {
            drawIndex = getVisibleIndexForGlobalLine(globalLine);
        }
        return (drawIndex - drawBaseLine) * lineHeight;
    }

    private float getDrawLineBottom(int globalLine) {
        return getDrawLineTop(globalLine) + lineHeight;
    }

    private float getHitTestBaseY() {
        int baseLine = (int) (scrollY / lineHeight);
        if (baseLine < 0) baseLine = 0;
        return baseLine * lineHeight;
    }

    private final LinkedHashMap<Integer, int[]> colorCodeBgCache =
            new LinkedHashMap<Integer, int[]>(600, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
                    return size() > 600;
                }
            };
    private HighlightRule whitespaceStringRule;
    private HighlightRule whitespaceCommentRule;
    private static final String WHITESPACE_GUIDE_SPACE = "\u00B7";
    private static final String WHITESPACE_GUIDE_TAB = "\u2192";
    private static final String FOLD_PLACEHOLDER_TEXT = "<—>";
    private float foldPlaceholderCorner = 3f;
    private float foldPlaceholderPadX = 3f;
    private float foldPlaceholderPadY = 2f;
    private final java.util.HashMap<Integer, FoldRange> foldRanges = new java.util.HashMap<>();
    private final java.util.ArrayList<int[]> foldIntervals = new java.util.ArrayList<>();
    private boolean foldIntervalsDirty = true;

    // --- Current Line Highlight State ---
    private boolean highlightCurrentLine = true;
    private boolean isClickAfterEndToAddLineEnabled = false;
    private boolean isAutoPairingEnabled = false;
    private boolean isAutoBracketNewlineEnabled = false;
    private boolean isAutoBracketNewlineIndentEnabled = false;
    private boolean isCodeFoldingEnabled = false;
    private int currentLineHighlightColor = 0x202196F3; // Default: translucent gray (more visible)
    private int currentLineNumberColor = 0xFF2196F3; // Default: same as cursor/handles color
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
    private boolean showLoadingOnFileOpen = true;
    private boolean isInitialFileOpenLoading = false;
    private int initialFileOpenToken = 0;
    @Nullable private Runnable initialFileOpenShowSpinner;
    private int maxWidthRecalcToken = 0;

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


    // --- Syntax Highlighting State ---
    private final List<HighlightRule> highlightRules = new ArrayList<>();
    private HighlightRule stringHighlightRule;
    private HighlightRule blockCommentHighlightRule;
    private final ArrayList<HighlightRule> regexHighlightRules = new ArrayList<>();
    private final LinkedHashMap<Integer, List<HighlightSpan>> highlightCache =
        new LinkedHashMap<Integer, List<HighlightSpan>>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<HighlightSpan>> eldest) {
                return size() > 1000;
            }
        };
    private final LinkedHashMap<Integer, Boolean> blockCommentEndStateCache =
        new LinkedHashMap<Integer, Boolean>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                return size() > 1000;
            }
        };
    private final LinkedHashMap<Integer, Integer> stringEndStateCache =
        new LinkedHashMap<Integer, Integer>(1000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > 1000;
            }
        };

    // --- URL underline (decoration, not syntax) ---
    private static final Pattern DEFAULT_URL_UNDERLINE_PATTERN = Pattern.compile("https?://[^\\s]+");
    private boolean isUrlUnderliningEnabled = false;
    @Nullable private Pattern urlUnderlinePattern = DEFAULT_URL_UNDERLINE_PATTERN;
    private final Paint urlUnderlineTmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinkedHashMap<Integer, List<UnderlineSpan>> urlUnderlineCache =
            new LinkedHashMap<Integer, List<UnderlineSpan>>(1000, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, List<UnderlineSpan>> eldest) {
                    return size() > 1000;
                }
            };

    public static final String RULE_STRING = "__STRING__";
    public static final String RULE_BLOCK_COMMENT = "__BLOCK_COMMENT__";

    // --- Auto-completion State ---
    private boolean isAutoCompletionEnabled = false;
    private final Paint suggestionPaint = new Paint();
    private String activeSuggestion = null;
    private int activeSuggestionLine;
    private int activeSuggestionCharStart; // character index where the word fragment starts
    private String activeSuggestionWordFragment = ""; // the part user typed
    private final Trie suggestionTrie = new Trie();
    private final RectF activeSuggestionRect = new RectF(); // For tap-to-accept
    private boolean isSuggestionTextSizeCustom = false; // Flag to track if suggestion text size is custom
    private boolean suggestionAcceptedThisTouch = false; // Flag to prevent GestureDetector interference

    private static class TrieNode {
        final Map<Character, TrieNode> children = new java.util.TreeMap<>();
        String word = null;
    }

    private static class Trie {
        private final TrieNode root = new TrieNode();

        public void clear() {
            root.children.clear();
            root.word = null;
        }

        public void insert(String word) {
            if (word == null || word.isEmpty()) return;
            TrieNode current = root;
            for (char l : word.toCharArray()) {
                current = current.children.computeIfAbsent(l, c -> new TrieNode());
            }
            current.word = word;
        }

        public String findFirstSuggestion(String prefix) {
            if (prefix == null || prefix.isEmpty()) return null;
            TrieNode current = root;
            for (char l : prefix.toCharArray()) {
                TrieNode node = current.children.get(l);
                if (node == null) {
                    return null;
                }
                current = node;
            }
            String suggestion = findFirstWordFromNode(current);
            // Don't suggest the exact word the user has already typed.
            if (suggestion != null && suggestion.equals(prefix)) {
                return null;
            }
            return suggestion;
        }

        private String findFirstWordFromNode(TrieNode node) {
            if (node == null) return null;
            // Traverse to the first word available from this node.
            if (node.word != null) {
                return node.word;
            }
            // Using TreeMap in TrieNode makes this loop alphabetically deterministic.
            for (TrieNode childNode : node.children.values()) {
                String found = findFirstWordFromNode(childNode);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }


    private static class HighlightSpan {
        final int start;
        final int end;
        final Paint paint;
        HighlightSpan(int start, int end, Paint paint) {
            this.start = start; this.end = end; this.paint = paint;
        }
    }

    private static class UnderlineSpan {
        final int start;
        final int end;
        UnderlineSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class LineParseResult {
        final List<HighlightSpan> spans;
        final boolean endsInBlockComment;
        final int endsInStringState;
        LineParseResult(List<HighlightSpan> spans, boolean endsInBlockComment, int endsInStringState) {
            this.spans = spans;
            this.endsInBlockComment = endsInBlockComment;
            this.endsInStringState = endsInStringState;
        }
    }

    private static class HighlightLineState {
        final boolean inBlockComment;
        final int stringState;
        HighlightLineState(boolean inBlockComment, int stringState) {
            this.inBlockComment = inBlockComment;
            this.stringState = stringState;
        }
    }

    private static class BracketMatch {
        final int openLine;
        final int openChar;
        final int closeLine;
        final int closeChar;
        BracketMatch(int openLine, int openChar, int closeLine, int closeChar) {
            this.openLine = openLine;
            this.openChar = openChar;
            this.closeLine = closeLine;
            this.closeChar = closeChar;
        }
    }

    private static final class FoldRange {
        final int startLine;
        final int endLine;
        final int openCharIndex;
        final char openChar;
        final char closeChar;
        final boolean isBlockComment;
        boolean collapsed;

        FoldRange(int startLine, int endLine, int openCharIndex, char openChar, char closeChar, boolean isBlockComment) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.openCharIndex = openCharIndex;
            this.openChar = openChar;
            this.closeChar = closeChar;
            this.isBlockComment = isBlockComment;
            this.collapsed = false;
        }
    }

    private static class BracketToken {
        final int line;
        final int ch;
        final char bracket;
        BracketToken(int line, int ch, char bracket) {
            this.line = line;
            this.ch = ch;
            this.bracket = bracket;
        }
    }

    private static class BracketGuideState {
        boolean inBlockComment;
        int stringState;
        final java.util.ArrayDeque<BracketGuideToken> stack = new java.util.ArrayDeque<>();
        BracketGuideState(boolean inBlockComment, int stringState) {
            this.inBlockComment = inBlockComment;
            this.stringState = stringState;
        }
    }

    private static class BracketGuideToken {
        final int column;
        final float x;
        BracketGuideToken(int column, float x) {
            this.column = column;
            this.x = x;
        }
    }

    private enum HighlightRuleType {
        REGEX,
        STRING,
        BLOCK_COMMENT
    }

    private static class HighlightRule {
        final HighlightRuleType type;
        final Pattern pattern;
        final Paint paint;
        final int style;
        final boolean underline;

        HighlightRule(
                String regex,
                int style,
                int color,
                float baseTextSize,
                Typeface baseTypeface,
                boolean underline,
                HighlightRuleType type
        ) {
            this.type = type;
            if (type == HighlightRuleType.REGEX) {
                // Handle comments regex gracefully
                if (regex.equals("//*|#*")) {
                    regex = "(//|#).*";
                }
                this.pattern = Pattern.compile(regex);
            } else {
                this.pattern = null;
            }
            this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.paint.setColor(color);
            this.paint.setTextSize(baseTextSize);
            this.style = style;
            this.underline = underline;

            int typefaceStyle;
            switch (style) {
                case STYLE_BOLD: typefaceStyle = Typeface.BOLD; break;
                case STYLE_ITALIC: typefaceStyle = Typeface.ITALIC; break;
                case STYLE_BOLD_ITALIC: typefaceStyle = Typeface.BOLD_ITALIC; break;
                default: typefaceStyle = Typeface.NORMAL; break;
            }

            this.paint.setTypeface(Typeface.create(baseTypeface, typefaceStyle));
            this.paint.setUnderlineText(underline);
        }

        void updateTextSize(float size) {
            paint.setTextSize(size);
        }
    }


    // --- Color Code Highlighting ---
    private boolean isColorHighlightingEnabled = false;
    private boolean isMultiLineStringsEnabled = true;
    private boolean isBacktickStringsEnabled = true;
    private boolean isBlockCommentsEnabled = true;
    private boolean isTripleQuoteStringsEnabled = true;
    private final Paint colorOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Pattern COLOR_HEX_PATTERN = Pattern.compile(
        "(#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}))\\b|(\\b0x[a-fA-F0-9]{6,8}\\b)",
        Pattern.CASE_INSENSITIVE
    );


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
        bracketMatchPaint.setColor(cursorAndHandlesColor);
        bracketMatchPaint.setStyle(Paint.Style.STROKE);
        bracketMatchPaint.setStrokeWidth(bracketMatchStrokeWidth);
        bracketGuidePaint.setColor(0xFF888888);
        bracketGuidePaint.setStyle(Paint.Style.STROKE);
        bracketGuidePaint.setStrokeWidth(bracketGuideStrokeWidth);
        whitespaceGuidePaint.setColor(0xFF555555);
        whitespaceGuidePaint.setStyle(Paint.Style.FILL);
        whitespaceGuidePaint.setUnderlineText(false);
        updateWhitespaceGuideMetrics();
        whitespaceStringRule = new HighlightRule("", STYLE_NORMAL, 0xFF000000, paint.getTextSize(), paint.getTypeface(), false, HighlightRuleType.STRING);
        whitespaceCommentRule = new HighlightRule("", STYLE_NORMAL, 0xFF000000, paint.getTextSize(), paint.getTypeface(), false, HighlightRuleType.BLOCK_COMMENT);

        selectionPaint.setStyle(Paint.Style.FILL);
        caretPaint.setStyle(Paint.Style.STROKE);
        caretPaint.setStrokeCap(Paint.Cap.BUTT);
        handlePaint.setStyle(Paint.Style.FILL);
        loadingCirclePaint.setStyle(Paint.Style.STROKE);
        loadingCirclePaint.setStrokeCap(Paint.Cap.ROUND);

        // Initialization for line numbers
        lineNumbersPaint.setTextAlign(Paint.Align.RIGHT);
        lineNumbersPaint.setColor(0xFF888888); // Default gray color
        lineNumbersPaint.setTextSize(paint.getTextSize());
        gutterPaint.setColor(0xFFFAFAFA); // Default light gray background

        float density = getContext().getResources().getDisplayMetrics().density;
        gutterSeparatorWidth = 4 * density;
        gutterSeparatorPaint.setColor(0xFF555555);
        currentLinePaint.setColor(currentLineHighlightColor);
        foldPlaceholderCorner = 6f * density;
        foldPlaceholderPadX = 6f * density;
        foldPlaceholderPadY = 2f * density;
        foldMarkerSpacing = 8f * density;
        foldMarkerEdgePadding = 6f * density;

        popupBgPaint.setColor(0xFF424242);
        popupTextPaint.setTextSize(30f);
        popupTextPaint.setColor(0xFFFFFFFF);
        popupTextPaint.setTextAlign(Paint.Align.LEFT);

        foldPlaceholderPaint.setColor(0xFFE0E0E0);
        foldPlaceholderPaint.setStyle(Paint.Style.FILL);
        foldMarkerPaint.setColor(0xFF888888);
        foldMarkerPaint.setTextAlign(isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        foldMarkerPaint.setTextSize(paint.getTextSize());

        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        scroller = new OverScroller(ctx);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                // If a suggestion was just accepted, clear the flag and allow normal onDown processing
                if (suggestionAcceptedThisTouch) {
                    suggestionAcceptedThisTouch = false; // Reset the flag
                    // DON'T return false; proceed with normal onDown logic
                }
                mJustFinishedScale = false;
                commitComposing(false); // End any active composing when user touches view.
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
                if (suggestionAcceptedThisTouch) return;

                
                if (movedSinceDown) return;

                if (isInLineNumberGutter(e.getX())) {
                    float y = e.getY() + scrollY;
                    int line = getGlobalLineForY(y);
                    beginLineNumberSelection(line);
                    return;
                }

                // Position calculation
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - getTextStartX();
                int line = getGlobalLineForY(y);
                ensureLineInWindow(line, true); // Make sure line data is available

                // Get line text safely
                String ln = getLineFromWindowLocal(line - windowStartLine);
                if (ln == null) ln = getLineTextForRender(line);

                int charIndex = getCharIndexForX(ln, x);

                // Check if the long press was on an "empty" area
                boolean isEmptyArea = false;
                if (ln.isEmpty()) {
                    isEmptyArea = true;
                } else if (charIndex >= ln.length()) {
                    isEmptyArea = true; // Tapped on empty space after the text on a line
                } else if (Character.isWhitespace(ln.charAt(charIndex))) {
                    isEmptyArea = true; // Tapped on a whitespace character
                }

                if (isEmptyArea) {
                    // This is a long press on an empty line or whitespace.
                    // 1. Set cursor position, hide any old selection/popup.
                    onSingleTapUp(e);
                    // 2. Show the minimal "Paste" / "Select All" popup.
                    showMinimalPopupAtCursor();

                } else {
                    // This is a long press on a word. Keep existing word selection logic.
                    isMinimalPopup = false;
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
                    restartInput();
                }
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (suggestionAcceptedThisTouch) return true;

                

                if (hasSelection) {
                    hasSelection = false;
                    isSelectAllActive = false;
                    isEntireFileSelected = false;
                }
                if (isCodeFoldingEnabled && isInLineNumberGutter(e.getX())) {
                    float gy = e.getY() + scrollY;
                    int line = getGlobalLineForY(gy);
                    if (toggleFoldAtLine(line)) {
                        hidePopup();
                        invalidate();
                        return true;
                    }
                }
                float y = e.getY() + scrollY;
                int visibleIndex = Math.max(0, (int) (y / lineHeight));
                float x = e.getX() + scrollX - getTextStartX();
                int line = getGlobalLineForY(y);

                boolean afterEnd = isEof && line >= windowStartLine + linesWindow.size() && !linesWindow.isEmpty();
                if (isCodeFoldingEnabled) {
                    afterEnd = visibleIndex >= getVisibleLineCount();
                }

                if (afterEnd) {
                    if (isClickAfterEndToAddLineEnabled) {
                        int lastLineIndex = windowStartLine + linesWindow.size() - 1;
                        // Only add a new line if the user taps exactly on the first empty line after the text
                        if (visibleIndex == getVisibleLineCount()) {
                            cursorLine = lastLineIndex;
                            String lastLineText = getLineTextForRender(cursorLine);
                            cursorChar = lastLineText.length();
                            insertTextAtCursor("\n");
                        } else {
                            // If tapped further down, just move cursor to end of text without adding lines
                            cursorLine = lastLineIndex;
                            String lastLineText = getLineTextForRender(cursorLine);
                            cursorChar = lastLineText.length();
                        }
                    } else {
                        cursorLine = windowStartLine + linesWindow.size() - 1;
                        String lastLineText = getLineTextForRender(cursorLine);
                        cursorChar = lastLineText.length();
                    }
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
                restartInput();
                updateSuggestion();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (e2.getPointerCount() > 1) return true;
                if (isScaling || scaleGestureDetector.isInProgress()) return true;
                if (mJustFinishedScale) return true;
                if (suggestionAcceptedThisTouch) return false; // Don't process if suggestion was accepted
                
                movedSinceDown = true;
                scrollY += distanceY;
                scrollX += distanceX;
                clampScrollY();
                clampScrollX();

                removeCallbacks(delayedWindowCheck);
                if (Math.abs(distanceY) > lineHeight * 6f) {
                    checkAndLoadWindow();
                }
                else {
                    postDelayed(delayedWindowCheck, 60);
                }

                if (showPopup) hidePopup();
                resetCursorBlink();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (isScaling || scaleGestureDetector.isInProgress()) return true;
                if (mJustFinishedScale) return true;
                if (suggestionAcceptedThisTouch) return false; // Don't process if suggestion was accepted
                
                int startX = Math.round(scrollX);
                int startY = Math.round(scrollY);
                int minX = 0;
                int maxX = Math.max(0, Math.round(getMaxLineWidthInWindow() - (getWidth() - getTextStartX())));
                int minY = 0;

                float maxScrollYFloat;
                float effectiveHeight = (keyboardHeight > 0) ? getHeight() - keyboardHeight : getHeight();

                int lineCount = isCodeFoldingEnabled ? getVisibleLineCount() : (windowStartLine + linesWindow.size());
                if (isEof) {
                    float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
                    maxScrollYFloat = Math.max(0f, lineCount * lineHeight - (effectiveHeight - paddingToUse));
                } else {
                    float virtualExtraSpace = Math.max(prefetchLines * lineHeight, 2000f);
                    maxScrollYFloat = Math.max(0f, lineCount * lineHeight + virtualExtraSpace - effectiveHeight);
                }
                int maxY = Math.max(0, Math.round(maxScrollYFloat));

                removeCallbacks(delayedWindowCheck);
                scroller.fling(startX, startY, (int) -velocityX, (int) -velocityY, minX, maxX, minY, maxY);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (suggestionAcceptedThisTouch) return true; // Don't process if suggestion was accepted
                float y = e.getY() + scrollY;
                float x = e.getX() + scrollX - getTextStartX();
                int line = getGlobalLineForY(y);
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
                restartInput();
                return true;
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                mJustFinishedScale = false;
                isScaling = true;
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isZoomEnabled) {
                    return false;
                }

                float scale = detector.getScaleFactor();
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();

                // Zoom
                float oldLineHeight = paint.getFontSpacing();
                float currentSize = paint.getTextSize();
                float newSize = currentSize * scale;

                newSize = Math.max(MIN_TEXT_SIZE, Math.min(newSize, MAX_TEXT_SIZE));

                if (Math.abs(newSize - currentSize) > 0.1f) {
                    setTextSize(newSize);
                    float newLineHeight = paint.getFontSpacing();
                    float effectiveScaleY = (oldLineHeight > 0) ? newLineHeight / oldLineHeight : 1f;

                    // Adjust scroll to make zoom appear centered on the focal point
                    scrollX = (scrollX + focusX - getTextStartX()) * scale - (focusX - getTextStartX());
                    scrollY = (scrollY + focusY) * effectiveScaleY - focusY;
                }

                lastFocusX = focusX;
                lastFocusY = focusY;

                clampScrollX();
                clampScrollY();
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mJustFinishedScale = true;
                isScaling = false;
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

        // Initialize suggestion paint
        suggestionPaint.set(paint);
        suggestionPaint.setColor(0xFFAAAAAA); // Default faint gray
        suggestionPaint.setAntiAlias(true);
        suggestionPaint.setSubpixelText(true);
        suggestionPaint.setHinting(Paint.HINTING_ON);
        isSuggestionTextSizeCustom = false; // By default, suggestion size follows main text
    }

    // --- Public APIs for Auto Completion ---

    public void setAutoCompletionEnabled(boolean enabled) {
        this.isAutoCompletionEnabled = enabled;
        if (!enabled) {
            clearActiveSuggestion();
        }
        invalidate();
    }

    public void setSuggestions(List<String> keywords, int color) {
        suggestionTrie.clear();
        if (keywords != null) {
            for (String word : keywords) {
                suggestionTrie.insert(word);
            }
        }
        // Only set the color. Size and style are synced automatically.
        suggestionPaint.setColor(color);
        clearActiveSuggestion();
    }

    public void acceptAutoCompletion() {
        Log.d("PopEditText", "acceptAutoCompletion: Entered.");
        if (!isAutoCompletionEnabled || activeSuggestion == null) {
            Log.d("PopEditText", "acceptAutoCompletion: Bailed out (disabled or no active suggestion).");
            return;
        }

        commitComposing(false);

        // Set a flag to ignore subsequent gesture events from this touch sequence.
        suggestionAcceptedThisTouch = true;

        String textToInsert = activeSuggestion;
        clearActiveSuggestion();
        hasSelection = false; // Clear selection after accepting suggestion
        isSelectAllActive = false;
        isEntireFileSelected = false;
        Log.d("PopEditText", "acceptAutoCompletion: Cleared selection flags, inserting text.");
        insertStringAtCursor(textToInsert);
        Log.d("PopEditText", "acceptAutoCompletion: Text inserted.");

        restartInput(); // Force IME to resync

        // The flag will be reset by the next onDown event.
    }

    public void setSuggestionTextSize(float size) {
        suggestionPaint.setTextSize(size);
        invalidate();
    }

    // --- Core Logic for Auto Completion ---

    private void clearActiveSuggestion() {
        if (activeSuggestion != null) {
            activeSuggestion = null;
            activeSuggestionRect.setEmpty();
            invalidate();
        }
    }

    private void updateSuggestion() {
        if (!isAutoCompletionEnabled) {
            clearActiveSuggestion();
            return;
        }

        String line = getLineTextForRender(cursorLine);

        // Prevent suggestions inside syntax highlighting
        List<HighlightSpan> spans = highlightCache.get(cursorLine);
        if (spans == null) {
            spans = calculateSpansForLine(line, cursorLine);
            highlightCache.put(cursorLine, spans);
        }
        for (HighlightSpan span : spans) {
            if (cursorChar > span.start && cursorChar <= span.end) {
                clearActiveSuggestion();
                return;
            }
        }

        // Do not show suggestions if the cursor is in the middle of a word
        if (cursorChar < line.length() && Character.isLetterOrDigit(line.charAt(cursorChar))) {
            clearActiveSuggestion();
            return;
        }

        // Do not show suggestions if there is non-whitespace text after the cursor
        if (cursorChar < line.length()) {
            if (!line.substring(cursorChar).trim().isEmpty()) {
                clearActiveSuggestion();
                return;
            }
        }

        String wordFragment = getCurrentWordFragment();
        if (wordFragment.isEmpty()) {
            clearActiveSuggestion();
            return;
        }

        String suggestion = suggestionTrie.findFirstSuggestion(wordFragment);
        if (suggestion != null && suggestion.length() > wordFragment.length()) {
            activeSuggestion = suggestion.substring(wordFragment.length());
            activeSuggestionLine = cursorLine;
            activeSuggestionCharStart = cursorChar - wordFragment.length();
            activeSuggestionWordFragment = wordFragment;
        } else {
            clearActiveSuggestion();
        }
        invalidate();
    }

    private String getCurrentWordFragment() {
        String line = getLineTextForRender(cursorLine);
        if (cursorChar == 0 || cursorChar > line.length()) {
            return "";
        }
        int start = cursorChar;
        // A word character is a letter or a digit.
        while (start > 0 && Character.isLetterOrDigit(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, cursorChar);
    }

    private void insertStringAtCursor(String text) {
        if (text == null || text.isEmpty()) return;
        if (hasSelection) {
            replaceSelectionWithText(text);
            return;
        }
        if (text.contains("\n")) { // Not handled for simplicity, suggestions shouldn't have newlines.
            for(char c : text.toCharArray()) insertCharAtCursor(c);
            return;
        }
        invalidatePendingIO();
        editVersion.incrementAndGet();

        ensureLineInWindow(cursorLine, true);
        if (isWindowLoading && (cursorLine < windowStartLine || cursorLine >= windowStartLine + linesWindow.size())) {
            post(() -> insertStringAtCursor(text));
            return;
        }

        int localIdx = cursorLine - windowStartLine;
        synchronized (linesWindow) {
            String base = getLineFromWindowLocal(localIdx);
            if(base == null) base = "";
            int pos = Math.max(0, Math.min(cursorChar, base.length()));
            String modified = base.substring(0, pos) + text + base.substring(pos);
            updateLocalLine(localIdx, modified);
            modifiedLines.put(cursorLine, modified);
            invalidateHighlightCacheForLine(cursorLine);
            cursorChar += text.length();
            computeWidthForLine(cursorLine, modified);
            recalculateMaxLineWidth();
            keepCursorVisibleHorizontally();
            invalidate();
        }
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

    public void setSelectionColor(int color) {
        setSelectionHighlightColor(color);
    }

    public void setCursorAndHandlesColor(int color) {
        if (this.cursorAndHandlesColor == color) return;
        this.cursorAndHandlesColor = color;
        invalidate();
    }

    public void setBracketMatchingEnabled(boolean enabled) {
        if (this.isBracketMatchingEnabled == enabled) return;
        this.isBracketMatchingEnabled = enabled;
        if (!enabled) {
            cachedBracketMatch = null;
            cachedBracketMatchCursorLine = -1;
            cachedBracketMatchCursorChar = -1;
            cachedBracketMatchEditVersion = -1;
        }
        invalidate();
    }

    public void setBracketMatchColor(int color) {
        bracketMatchPaint.setColor(color);
        invalidate();
    }

    public void setBracketMatchStrokeWidth(float width) {
        if (this.bracketMatchStrokeWidth == width) return;
        this.bracketMatchStrokeWidth = width;
        bracketMatchPaint.setStrokeWidth(width);
        invalidate();
    }

    public void setBracketGuidesEnabled(boolean enabled) {
        if (this.isBracketGuidesEnabled == enabled) return;
        this.isBracketGuidesEnabled = enabled;
        invalidate();
    }

    public void setBracketGuidesColor(int color) {
        bracketGuidePaint.setColor(color);
        invalidate();
    }

    public void setBracketGuidesStrokeWidth(float width) {
        if (this.bracketGuideStrokeWidth == width) return;
        this.bracketGuideStrokeWidth = width;
        bracketGuidePaint.setStrokeWidth(width);
        invalidate();
    }

    public void setWhitespaceGuidesEnabled(boolean enabled) {
        if (this.isWhitespaceGuidesEnabled == enabled) return;
        this.isWhitespaceGuidesEnabled = enabled;
        synchronized (lineWidthCache) { lineWidthCache.clear(); }
        currentMaxWindowLineWidth = 0f;
        globalMaxLineWidth = 0f;
        recalculateMaxLineWidth();
        invalidate();
    }

    public void setWhitespaceGuidesColor(int color) {
        whitespaceGuidePaint.setColor(color);
        if (isWhitespaceGuidesEnabled) invalidate();
    }

    public void setWhitespaceGuidesSpaceStep(int spacesPerDot) {
        int safeStep = Math.max(1, spacesPerDot);
        if (whitespaceGuideSpaceStep == safeStep) return;
        whitespaceGuideSpaceStep = safeStep;
        synchronized (lineWidthCache) { lineWidthCache.clear(); }
        currentMaxWindowLineWidth = 0f;
        globalMaxLineWidth = 0f;
        recalculateMaxLineWidth();
        if (isWhitespaceGuidesEnabled) invalidate();
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

    public void setClickAfterEndToAddLineEnabled(boolean enabled) {
        this.isClickAfterEndToAddLineEnabled = enabled;
    }

    public void setAutoPairingEnabled(boolean enabled) {
        this.isAutoPairingEnabled = enabled;
    }

    public void setAutoBracketNewlineEnabled(boolean enabled) {
        this.isAutoBracketNewlineEnabled = enabled;
    }

    public void setAutoBracketNewlineIndentEnabled(boolean enabled) {
        this.isAutoBracketNewlineIndentEnabled = enabled;
    }

    public void setCodeFoldingEnabled(boolean enabled) {
        if (this.isCodeFoldingEnabled == enabled) return;
        this.isCodeFoldingEnabled = enabled;
        if (!enabled) foldRanges.clear();
        foldIntervalsDirty = true;
        invalidate();
    }

    public void setFoldPlaceholderColor(int color) {
        foldPlaceholderPaint.setColor(color);
        if (isCodeFoldingEnabled) invalidate();
    }

    public void setFoldMarkerTextSize(float size) {
        float base = paint.getTextSize();
        if (base <= 0f) return;
        foldMarkerTextScale = size / base;
        foldMarkerPaint.setTextSize(base * foldMarkerTextScale);
        requestLayout();
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

    public void setGutterSeparatorWidth(float width) {
        float safe = Math.max(0f, width);
        if (gutterSeparatorWidth == safe) return;
        gutterSeparatorWidth = safe;
        requestLayout();
        if (showLineNumbers) invalidate();
    }

    public void setCurrentLineNumberColor(int color) {
        if (this.currentLineNumberColor == color) return;
        this.currentLineNumberColor = color;
        if (showLineNumbers) invalidate();
    }

    public void addHighlightRule(String regex, int style, int color) {
        addHighlightRule(regex, style, color, false);
    }

    public void addHighlightRule(String regex, int style, int color, boolean underline) {
        HighlightRuleType type = HighlightRuleType.REGEX;
        if (RULE_STRING.equals(regex)) {
            type = HighlightRuleType.STRING;
        } else if (RULE_BLOCK_COMMENT.equals(regex)) {
            type = HighlightRuleType.BLOCK_COMMENT;
        }
        HighlightRule rule = new HighlightRule(regex, style, color, paint.getTextSize(), paint.getTypeface(), underline, type);
        highlightRules.add(rule);
        if (type == HighlightRuleType.STRING) {
            stringHighlightRule = rule;
        } else if (type == HighlightRuleType.BLOCK_COMMENT) {
            blockCommentHighlightRule = rule;
        } else {
            regexHighlightRules.add(rule);
        }
        clearHighlightCaches();
        invalidate();
    }

    public void clearHighlightRules() {
        highlightRules.clear();
        stringHighlightRule = null;
        blockCommentHighlightRule = null;
        regexHighlightRules.clear();
        clearHighlightCaches();
        invalidate();
    }

    private void clearHighlightCaches() {
        highlightCache.clear();
        blockCommentEndStateCache.clear();
        stringEndStateCache.clear();
        colorCodeBgCache.clear();
        urlUnderlineCache.clear();
    }

    private void invalidateHighlightCacheForLine(int line) {
        highlightCache.remove(line);
        blockCommentEndStateCache.clear();
        stringEndStateCache.clear();
        colorCodeBgCache.remove(line);
        urlUnderlineCache.remove(line);
    }

    public void setUrlUnderliningEnabled(boolean enabled) {
        if (this.isUrlUnderliningEnabled == enabled) return;
        this.isUrlUnderliningEnabled = enabled;
        urlUnderlineCache.clear();
        invalidate();
    }

    public void setUrlUnderliningRegex(@Nullable String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            this.urlUnderlinePattern = null;
        } else {
            this.urlUnderlinePattern = Pattern.compile(regex);
        }
        urlUnderlineCache.clear();
        invalidate();
    }

    public void setColorHighlightingEnabled(boolean enabled) {
        if (isColorHighlightingEnabled == enabled) return;
        isColorHighlightingEnabled = enabled;
        invalidate();
    }

    public void setMultiLineStringsEnabled(boolean enabled) {
        if (isMultiLineStringsEnabled == enabled) return;
        isMultiLineStringsEnabled = enabled;
        clearHighlightCaches();
        invalidate();
    }

    public void setMultiLineStringsHighlight(boolean enabled, int color) {
        if (stringHighlightRule == null) {
            addHighlightRule(RULE_STRING, STYLE_NORMAL, color);
        }
        if (stringHighlightRule != null) {
            stringHighlightRule.paint.setColor(color);
        }
        setMultiLineStringsEnabled(enabled);
    }

    public void setMultiLineStringsHighlite(boolean enabled, int color) {
        setMultiLineStringsHighlight(enabled, color);
    }

    public void setBacktickStringsEnabled(boolean enabled) {
        if (isBacktickStringsEnabled == enabled) return;
        isBacktickStringsEnabled = enabled;
        clearHighlightCaches();
        invalidate();
    }

    public void setBlockCommentsEnabled(boolean enabled) {
        if (isBlockCommentsEnabled == enabled) return;
        isBlockCommentsEnabled = enabled;
        clearHighlightCaches();
        invalidate();
    }

    public void setMultiLineCommentsHighlight(boolean enabled, int color) {
        if (blockCommentHighlightRule == null) {
            addHighlightRule(RULE_BLOCK_COMMENT, STYLE_ITALIC, color);
        }
        if (blockCommentHighlightRule != null) {
            blockCommentHighlightRule.paint.setColor(color);
        }
        setBlockCommentsEnabled(enabled);
    }

    public void setMultiLineCommentsHighlite(boolean enabled, int color) {
        setMultiLineCommentsHighlight(enabled, color);
    }

    public void setTripleQuoteStringsEnabled(boolean enabled) {
        if (isTripleQuoteStringsEnabled == enabled) return;
        isTripleQuoteStringsEnabled = enabled;
        clearHighlightCaches();
        invalidate();
    }

    public void setLayoutDirection(boolean isRtl) {
        if (this.isRtl == isRtl) return;
        this.isRtl = isRtl;
        lineNumbersPaint.setTextAlign(isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        foldMarkerPaint.setTextAlign(isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        requestLayout();
        invalidate();
    }
    
    public void setTextSize(float size) {
        float oldSize = paint.getTextSize();
        if (Math.abs(size - oldSize) < 0.1f) return;

        paint.setTextSize(size);
        // Only update suggestionPaint if it's not custom-set
        if (!isSuggestionTextSizeCustom) {
            suggestionPaint.setTextSize(size);
        }
        lineNumbersPaint.setTextSize(size);
        foldMarkerPaint.setTextSize(size * foldMarkerTextScale);
        lineHeight = paint.getFontSpacing();
        updateWhitespaceGuideMetrics();

        for (HighlightRule rule : highlightRules) {
            rule.updateTextSize(size);
        }
        if (whitespaceStringRule != null) whitespaceStringRule.updateTextSize(size);
        if (whitespaceCommentRule != null) whitespaceCommentRule.updateTextSize(size);
        clearHighlightCaches();

        // Invalidate caches and approximate new max width
        synchronized (lineWidthCache) {
            lineWidthCache.clear();
        }
        // Scale the max width instead of recalculating it synchronously.
        // This is an approximation but avoids massive lag.
        float scale = size / oldSize;
        currentMaxWindowLineWidth *= scale;
        globalMaxLineWidth *= scale;

        requestLayout(); // Still needed for gutter
        invalidate();
    }

    private void updateWhitespaceGuideMetrics() {
        whitespaceGuidePaint.setTextSize(paint.getTextSize());
        whitespaceGuidePaint.setTypeface(paint.getTypeface());
        whitespaceGuideSpaceWidth = whitespaceGuidePaint.measureText(WHITESPACE_GUIDE_SPACE);
        whitespaceGuideTabWidth = whitespaceGuidePaint.measureText(WHITESPACE_GUIDE_TAB);
    }

    private int writeIntToChars(int value, char[] out) {
        int v = value;
        int pos = out.length;
        if (v == 0) {
            out[--pos] = '0';
            return pos;
        }
        while (v > 0 && pos > 0) {
            int digit = v % 10;
            out[--pos] = (char) ('0' + digit);
            v /= 10;
        }
        return pos;
    }

    private void ensureHighlightCacheForVisibleRange(int firstVisibleLine, int lastVisibleLine, @Nullable java.util.HashMap<Integer, String> directLines) {
        if (highlightRules.isEmpty()) return;
        if (firstVisibleLine > lastVisibleLine) return;

        HighlightRule stringRule = stringHighlightRule;
        HighlightRule blockRule = blockCommentHighlightRule;
        boolean needSyntax = stringRule != null || blockRule != null;
        boolean needRegex = !regexHighlightRules.isEmpty();
        if (!needSyntax && !needRegex) return;

        boolean inBlock = false;
        int stringState = 0;
        final int localWindowStart = windowStartLine;
        final int localWindowEnd;
        synchronized (linesWindow) {
            localWindowEnd = localWindowStart + linesWindow.size();
        }

        if (needSyntax) {
            int prevLine = firstVisibleLine - 1;
            Boolean cachedBlockPrev = blockCommentEndStateCache.get(prevLine);
            Integer cachedStringPrev = stringEndStateCache.get(prevLine);
            if (cachedBlockPrev != null && cachedStringPrev != null) {
                inBlock = cachedBlockPrev;
                stringState = cachedStringPrev;
            } else {
                int seedStart = localWindowStart;
                int seedEnd = Math.min(firstVisibleLine, localWindowEnd);
                for (int line = seedStart; line < seedEnd; line++) {
                    String seedLine = getLineTextForRenderWithDirect(line, directLines);
                    if (seedLine == null) seedLine = "";
                    LineParseResult seedResult = parseLineForSyntax(
                            seedLine,
                            inBlock,
                            stringState,
                            null,
                            null,
                            false
                    );
                    inBlock = seedResult.endsInBlockComment;
                    stringState = seedResult.endsInStringState;
                    if (line >= localWindowStart && line < localWindowEnd) {
                        if (isBlockCommentsEnabled) blockCommentEndStateCache.put(line, inBlock);
                        stringEndStateCache.put(line, stringState);
                    }
                    if (line + 1 == firstVisibleLine) break;
                }
            }
        }

        for (int globalLine = firstVisibleLine; globalLine <= lastVisibleLine; globalLine++) {
            List<HighlightSpan> cachedSpans = highlightCache.get(globalLine);
            boolean hasCachedState = true;
            Boolean cachedBlock = null;
            Integer cachedString = null;
            if (needSyntax && globalLine >= localWindowStart && globalLine < localWindowEnd) {
                cachedBlock = blockCommentEndStateCache.get(globalLine);
                cachedString = stringEndStateCache.get(globalLine);
                hasCachedState = cachedBlock != null && cachedString != null;
            }
            if (cachedSpans != null && (!needSyntax || hasCachedState)) {
                if (needSyntax && cachedBlock != null && cachedString != null) {
                    inBlock = cachedBlock;
                    stringState = cachedString;
                }
                continue;
            }

            String line = getLineTextForRenderWithDirect(globalLine, directLines);
            if (line == null) line = "";

            List<HighlightSpan> spans;
            if (needSyntax) {
                LineParseResult parseResult = parseLineForSyntax(
                        line,
                        inBlock,
                        stringState,
                        stringRule,
                        blockRule,
                        true
                );
                spans = parseResult.spans;
                inBlock = parseResult.endsInBlockComment;
                stringState = parseResult.endsInStringState;
                if (globalLine >= localWindowStart && globalLine < localWindowEnd) {
                    if (isBlockCommentsEnabled) blockCommentEndStateCache.put(globalLine, inBlock);
                    stringEndStateCache.put(globalLine, stringState);
                }
            } else {
                spans = new ArrayList<>();
            }

            if (needRegex && !line.isEmpty()) {
                for (HighlightRule rule : regexHighlightRules) {
                    Matcher matcher = rule.pattern.matcher(line);
                    while (matcher.find()) {
                        if (matcher.start() == matcher.end()) continue;
                        HighlightSpan span = new HighlightSpan(matcher.start(), matcher.end(), rule.paint);
                        if (hasOverlap(span, spans)) continue;
                        spans.add(span);
                    }
                }
            }

            if (spans.size() > 1) {
                Collections.sort(spans, (s1, s2) -> Integer.compare(s1.start, s2.start));
            }
            highlightCache.put(globalLine, spans);
        }
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
            float baseWidth = lineNumbersPaint.measureText(maxLineNum) + (GUTTER_TEXT_PADDING * 2);
            if (isCodeFoldingEnabled) {
                foldMarkerGutterWidth = foldMarkerPaint.measureText("v") + foldMarkerSpacing + foldMarkerEdgePadding;
            } else {
                foldMarkerGutterWidth = 0f;
            }
            lineNumbersGutterWidth = baseWidth + foldMarkerGutterWidth + gutterSeparatorWidth;
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

    private boolean isInLineNumberGutter(float x) {
        if (!showLineNumbers || lineNumbersGutterWidth <= 0f) return false;
        float start = getGutterStartX();
        return x >= start && x <= start + lineNumbersGutterWidth;
    }

    private int clampLineForSelection(int line) {
        if (line < 0) return 0;
        if (isEof) {
            int last = windowStartLine + linesWindow.size() - 1;
            if (last < 0) return 0;
            return Math.min(line, last);
        }
        return line;
    }

    private boolean isLineSelectable(int line) {
        ensureLineInWindow(line, true);
        String ln = getLineTextForRender(line);
        return ln != null && ln.length() > 0;
    }

    private void beginLineNumberSelection(int line) {
        int clamped = clampLineForSelection(line);
        if (!isLineSelectable(clamped)) return;
        clearActiveSuggestion();
        isLineNumberSelecting = true;
        lineNumberSelectAnchorLine = clamped;
        hasSelection = true;
        selecting = true;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        String lineText = getLineTextForRender(clamped);
        selStartLine = clamped;
        selStartChar = 0;
        selEndLine = clamped;
        selEndChar = lineText.length();
        cursorLine = clamped;
        cursorChar = selEndChar;
        hidePopup();
        resetCursorBlink();
        invalidate();
    }

    private void updateLineNumberSelection(int line) {
        if (!isLineNumberSelecting) return;
        int clamped = clampLineForSelection(line);
        if (!isLineSelectable(clamped)) return;
        int startLine = Math.min(lineNumberSelectAnchorLine, clamped);
        int endLine = Math.max(lineNumberSelectAnchorLine, clamped);
        ensureLineInWindow(endLine, true);
        String endLineText = getLineTextForRender(endLine);
        selStartLine = startLine;
        selStartChar = 0;
        selEndLine = endLine;
        selEndChar = endLineText.length();
        cursorLine = endLine;
        cursorChar = selEndChar;
        hasSelection = true;
        selecting = true;
        hidePopup();
        invalidate();
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

    private String buildFoldDisplayLine(String line, FoldRange range, int[] placeholderBoundsOut) {
        if (line == null) line = "";
        int placeholderStart = 0;
        int placeholderEnd = 0;
        String display;

        if (range.isBlockComment) {
            int safeIdx = Math.max(0, Math.min(range.openCharIndex, line.length()));
            String prefix = line.substring(0, safeIdx);
            placeholderStart = prefix.length() + 2;
            placeholderEnd = placeholderStart + FOLD_PLACEHOLDER_TEXT.length();
            display = prefix + "/*" + FOLD_PLACEHOLDER_TEXT + "*/";
        } else {
            int safeIdx = Math.max(0, Math.min(range.openCharIndex, Math.max(0, line.length() - 1)));
            String prefix = line.substring(0, safeIdx + 1);
            placeholderStart = prefix.length();
            placeholderEnd = placeholderStart + FOLD_PLACEHOLDER_TEXT.length();
            display = prefix + FOLD_PLACEHOLDER_TEXT + range.closeChar;
        }

        if (placeholderBoundsOut != null && placeholderBoundsOut.length >= 2) {
            placeholderBoundsOut[0] = placeholderStart;
            placeholderBoundsOut[1] = placeholderEnd;
        }
        return display;
    }

    private void drawFoldedLine(Canvas canvas, String line, int globalLine) {
        FoldRange range = foldRanges.get(globalLine);
        if (range == null) return;

        int[] bounds = new int[2];
        String displayLine = buildFoldDisplayLine(line, range, bounds);

        float y = Math.round(getDrawLineTop(globalLine) + lineHeight - paint.descent());
        float top = Math.round(getDrawLineTop(globalLine) + foldPlaceholderPadY);
        float bottom = Math.round(getDrawLineBottom(globalLine) - foldPlaceholderPadY);

        int startIdx = Math.min(bounds[0], displayLine.length());
        float xStart;
        if (range.isBlockComment) {
            int openEnd = Math.min(range.openCharIndex + 2, line.length());
            xStart = measureTextWithVisualSpaces(line, 0, openEnd, paint);
        } else {
            int openEnd = Math.min(range.openCharIndex + 1, line.length());
            xStart = measureTextWithVisualSpaces(line, 0, openEnd, paint);
        }
        float placeholderWidth = measureTextWithVisualSpaces(FOLD_PLACEHOLDER_TEXT, 0, FOLD_PLACEHOLDER_TEXT.length(), paint);
        foldPlaceholderRect.set(xStart, top, xStart + placeholderWidth, bottom);
        canvas.drawRoundRect(foldPlaceholderRect, foldPlaceholderCorner, foldPlaceholderCorner, foldPlaceholderPaint);

        paint.setUnderlineText(false);
        canvas.drawText(displayLine, 0f, y, paint);
    }

    private String getFoldMarkerForLine(int line, @Nullable String lineText) {
        if (!isCodeFoldingEnabled) return null;
        FoldRange range = foldRanges.get(line);
        if (range != null) return range.collapsed ? ">" : "v";
        if (lineText == null) return null;
        if (!shouldShowFoldMarkerFromLine(lineText)) return null;
        FoldRange found = findFoldRangeForLine(line);
        if (found == null) return null;
        foldRanges.put(found.startLine, found);
        foldIntervalsDirty = true;
        return "v";
    }

    private boolean shouldShowFoldMarkerFromLine(String line) {
        if (line == null || line.isEmpty()) return false;
        int blockStart = line.indexOf("/*");
        if (blockStart >= 0) {
            int blockEnd = line.indexOf("*/", blockStart + 2);
            if (blockEnd < 0) return true;
        }

        int idx = line.indexOf('{');
        if (idx >= 0 && line.indexOf('}', idx + 1) < 0) return true;
        idx = line.indexOf('(');
        if (idx >= 0 && line.indexOf(')', idx + 1) < 0) return true;
        idx = line.indexOf('[');
        if (idx >= 0 && line.indexOf(']', idx + 1) < 0) return true;
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Calculate visible line range
        int firstVisibleIndex = (int) (scrollY / lineHeight);
        if (firstVisibleIndex < 0) firstVisibleIndex = 0;
        int lastVisibleIndex = firstVisibleIndex + (int) Math.ceil(getHeight() / lineHeight) + 5;

        int firstVisibleLine = firstVisibleIndex;
        int lastVisibleLine = lastVisibleIndex;
        if (isCodeFoldingEnabled) {
            int visibleCount = getVisibleLineCount();
            if (visibleCount <= 0) visibleCount = 1;
            firstVisibleIndex = Math.max(0, Math.min(firstVisibleIndex, visibleCount - 1));
            lastVisibleIndex = Math.max(firstVisibleIndex, Math.min(lastVisibleIndex, visibleCount - 1));
            firstVisibleLine = mapVisibleIndexToGlobal(firstVisibleIndex);
            lastVisibleLine = mapVisibleIndexToGlobal(lastVisibleIndex);
            drawBaseLine = firstVisibleIndex;
        } else {
            drawBaseLine = firstVisibleLine;
        }

        float baseY = drawBaseLine * lineHeight;
        float translateY = -scrollY + baseY;
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
            canvas.translate(0, translateY);

            float lineNumX;
            if (isRtl) {
                lineNumX = getGutterStartX() + GUTTER_TEXT_PADDING + (isCodeFoldingEnabled ? foldMarkerGutterWidth : 0f);
            } else {
                lineNumX = getGutterStartX() + lineNumbersGutterWidth - (isCodeFoldingEnabled ? foldMarkerGutterWidth : 0f) - GUTTER_TEXT_PADDING;
            }

            if (isCodeFoldingEnabled) {
                for (int v = firstVisibleIndex; v <= lastVisibleIndex; v++) {
                    int i = mapVisibleIndexToGlobal(v);
                    int start = writeIntToChars(i + 1, lineNumberChars);
                    int count = lineNumberChars.length - start;
                    float y = Math.round(getDrawLineTop(i) + lineHeight - paint.descent());
                    if (i == cursorLine) {
                        int originalColor = lineNumbersPaint.getColor();
                        lineNumbersPaint.setColor(currentLineNumberColor);
                        canvas.drawText(lineNumberChars, start, count, lineNumX, y, lineNumbersPaint);
                        lineNumbersPaint.setColor(originalColor);
                    } else {
                        canvas.drawText(lineNumberChars, start, count, lineNumX, y, lineNumbersPaint);
                    }

                    String marker = getFoldMarkerForLine(i, getLineTextForRender(i));
                    if (marker != null) {
                        float markerX = isRtl
                                ? (getGutterStartX() + gutterSeparatorWidth + foldMarkerEdgePadding)
                                : (getGutterStartX() + lineNumbersGutterWidth - gutterSeparatorWidth - foldMarkerEdgePadding);
                        canvas.drawText(marker, markerX, y, foldMarkerPaint);
                    }
                }
            } else {
                for (int i = firstVisibleLine; i <= lastVisibleLine; i++) {
                    int start = writeIntToChars(i + 1, lineNumberChars);
                    int count = lineNumberChars.length - start;
                    float y = Math.round(getDrawLineTop(i) + lineHeight - paint.descent());
                    if (i == cursorLine) {
                        int originalColor = lineNumbersPaint.getColor();
                        lineNumbersPaint.setColor(currentLineNumberColor);
                        canvas.drawText(lineNumberChars, start, count, lineNumX, y, lineNumbersPaint);
                        lineNumbersPaint.setColor(originalColor);
                    } else {
                        canvas.drawText(lineNumberChars, start, count, lineNumX, y, lineNumbersPaint);
                    }
                }
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
        canvas.translate(getTextStartX() - scrollX, translateY);

        // --- This is the original text, selection, and handle drawing logic ---
        Paint selPaint = null;
        if (hasSelection) {
            selectionPaint.setColor(selectionHighlightColor);
            selPaint = selectionPaint;
        }

        java.util.HashMap<Integer, String> directLines = null;
        if (isIndexReady && sourceFile != null && sourceFile.exists()) {
            boolean needDirect = (firstVisibleLine < windowStartLine) ||
                    (firstVisibleLine >= windowStartLine + linesWindow.size()) ||
                    (lastVisibleLine >= windowStartLine + linesWindow.size());

            if (needDirect) {
                directLinesTmp.clear();
                directLines = directLinesTmp;
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

        BracketMatch bracketMatch = null;
        if (isBracketMatchingEnabled) {
            bracketMatch = findBracketMatchInVisible(firstVisibleLine, lastVisibleLine, directLines);
            int v = editVersion.get();
            if (bracketMatch != null) {
                cachedBracketMatch = bracketMatch;
                cachedBracketMatchCursorLine = cursorLine;
                cachedBracketMatchCursorChar = cursorChar;
                cachedBracketMatchEditVersion = v;
            } else if (cachedBracketMatch != null
                    && cachedBracketMatchCursorLine == cursorLine
                    && cachedBracketMatchCursorChar == cursorChar
                    && cachedBracketMatchEditVersion == v) {
                // Keep showing the last valid match when scrolling causes the cursor line to be
                // outside the render window (so we can't recompute reliably without IO).
                bracketMatch = cachedBracketMatch;
            }
        }

        ensureHighlightCacheForVisibleRange(firstVisibleLine, lastVisibleLine, directLines);

        BracketGuideState bracketGuideState = null;
        if (isBracketGuidesEnabled) {
            Boolean cachedBlockPrev = blockCommentEndStateCache.get(firstVisibleLine - 1);
            Integer cachedStringPrev = stringEndStateCache.get(firstVisibleLine - 1);
            HighlightLineState guideStart = (cachedBlockPrev != null && cachedStringPrev != null)
                    ? new HighlightLineState(cachedBlockPrev, cachedStringPrev)
                    : getLineStateAtStart(firstVisibleLine);
            boolean guideBlock = guideStart.inBlockComment && isBlockCommentsEnabled;
            int guideString = guideStart.stringState;
            if (!isBlockCommentsEnabled) guideBlock = false;
            if (!isMultiLineStringsEnabled && guideString != STRING_STATE_TRIPLE) guideString = 0;
            if (!isBacktickStringsEnabled && guideString == STRING_STATE_BACKTICK) guideString = 0;
            if (!isTripleQuoteStringsEnabled && guideString == STRING_STATE_TRIPLE) guideString = 0;
            bracketGuideState = new BracketGuideState(guideBlock, guideString);
            for (int line = windowStartLine; line < firstVisibleLine; line++) {
                String seedLine = getLineTextForRenderWithDirect(line, directLines);
                advanceBracketGuideStateForLine(seedLine, line, bracketGuideState);
            }
        }

        if (isCodeFoldingEnabled) {
            for (int v = firstVisibleIndex; v <= lastVisibleIndex; v++) {
                int globalLine = mapVisibleIndexToGlobal(v);
                String line = getLineTextForRenderWithDirect(globalLine, directLines);
                FoldRange foldRange = getFoldRangeAtStart(globalLine);
                boolean isFoldStart = (foldRange != null);

            // Highlight the current line, only if there is no selection
            if (highlightCurrentLine && globalLine == cursorLine && !hasSelection) {
                float top = Math.round(getDrawLineTop(globalLine));
                float bottom = Math.round(getDrawLineBottom(globalLine));
                canvas.drawRect(-paddingLeft, top, Math.max(getMaxLineWidthInWindow(), getWidth() + scrollX), bottom, currentLinePaint);
            }

            if (hasSelection && selPaint != null) {
                float top = Math.round(getDrawLineTop(globalLine));
                float bottom = Math.round(getDrawLineBottom(globalLine));
                float fullRight = Math.max(currentMaxWindowLineWidth, scrollX + (getWidth() - getTextStartX()));

                if (isSelectAllActive) {
                    boolean lineExists = (isEof) ? (globalLine <= windowStartLine + linesWindow.size() - 1) : true;
                    if (lineExists) {
                        drawSelectionSegment(canvas, 0, top, fullRight, bottom, true, true, true, true, selPaint);
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
                            left = measureText(line, Math.min(startChar, line.length()), globalLine);
                            right = measureText(line, Math.min(endChar, line.length()), globalLine);
                        } else {
                            if (globalLine == startLine) {
                                left = measureText(line, Math.min(startChar, line.length()), globalLine);
                                right = fullRight;
                            } else if (globalLine == endLine) {
                                left = 0;
                                right = measureText(line, Math.min(endChar, line.length()), globalLine);
                                if (line.length() == 0) right = fullRight;
                            } else {
                                left = 0;
                                right = fullRight;
                            }
                        }
                        if (right > left) {
                            boolean isSingle = startLine == endLine;
                            boolean isStart = globalLine == startLine;
                            boolean isEnd = globalLine == endLine;
                            boolean roundTop = isSingle || isStart;
                            boolean roundBottom = isSingle || isEnd;
                            drawSelectionSegment(canvas, left, top, right, bottom, roundTop, roundTop, roundBottom, roundBottom, selPaint);
                        }
                    }
                }
            }

            float y = Math.round(getDrawLineTop(globalLine) + lineHeight - paint.descent());
            paint.setUnderlineText(false); // Force disable underline before drawing

            // Draw color code backgrounds underneath the text
            drawColorCodeBackgrounds(canvas, line, globalLine);

            if (isFoldStart) {
                if (bracketGuideState != null) {
                    for (int h = globalLine; h <= foldRange.endLine; h++) {
                        String hl = getLineTextForRenderWithDirect(h, directLines);
                        advanceBracketGuideStateForLine(hl, h, bracketGuideState);
                    }
                }
                drawFoldedLine(canvas, line, globalLine);
                continue;
            }

            drawHighlightedLine(canvas, line, globalLine, y);
            drawWhitespaceGuidesForLine(canvas, line, globalLine, y);

            // Draw auto-completion suggestion
            drawAutoSuggestion(canvas, line, globalLine, y);

            if (bracketGuideState != null) {
                List<BracketGuideToken> guideTokens = updateBracketGuideStateForLine(line, globalLine, bracketGuideState);
                drawBracketGuidesForLine(canvas, line, globalLine, guideTokens);
            }

            drawBracketMatchForLine(canvas, line, globalLine, bracketMatch);
            }
        } else {
            for (int globalLine = firstVisibleLine; globalLine <= lastVisibleLine; globalLine++) {
                String line = getLineTextForRenderWithDirect(globalLine, directLines);

                // Highlight the current line, only if there is no selection
                if (highlightCurrentLine && globalLine == cursorLine && !hasSelection) {
                    float top = Math.round(getDrawLineTop(globalLine));
                    float bottom = Math.round(getDrawLineBottom(globalLine));
                    canvas.drawRect(-paddingLeft, top, Math.max(getMaxLineWidthInWindow(), getWidth() + scrollX), bottom, currentLinePaint);
                }

                if (hasSelection && selPaint != null) {
                    float top = Math.round(getDrawLineTop(globalLine));
                    float bottom = Math.round(getDrawLineBottom(globalLine));
                    float fullRight = Math.max(currentMaxWindowLineWidth, scrollX + (getWidth() - getTextStartX()));

                    if (isSelectAllActive) {
                        boolean lineExists = (isEof) ? (globalLine <= windowStartLine + linesWindow.size() - 1) : true;
                        if (lineExists) {
                            drawSelectionSegment(canvas, 0, top, fullRight, bottom, true, true, true, true, selPaint);
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
                                left = measureText(line, Math.min(startChar, line.length()), globalLine);
                                right = measureText(line, Math.min(endChar, line.length()), globalLine);
                            } else {
                                if (globalLine == startLine) {
                                    left = measureText(line, Math.min(startChar, line.length()), globalLine);
                                    right = fullRight;
                                } else if (globalLine == endLine) {
                                    left = 0;
                                    right = measureText(line, Math.min(endChar, line.length()), globalLine);
                                    if (line.length() == 0) right = fullRight;
                                } else {
                                    left = 0;
                                    right = fullRight;
                                }
                            }
                            if (right > left) {
                                boolean isSingle = startLine == endLine;
                                boolean isStart = globalLine == startLine;
                                boolean isEnd = globalLine == endLine;
                                boolean roundTop = isSingle || isStart;
                                boolean roundBottom = isSingle || isEnd;
                                drawSelectionSegment(canvas, left, top, right, bottom, roundTop, roundTop, roundBottom, roundBottom, selPaint);
                            }
                        }
                    }
                }

                float y = Math.round(getDrawLineTop(globalLine) + lineHeight - paint.descent());
                paint.setUnderlineText(false); // Force disable underline before drawing

                // Draw color code backgrounds underneath the text
                drawColorCodeBackgrounds(canvas, line, globalLine);

                drawHighlightedLine(canvas, line, globalLine, y);
                drawWhitespaceGuidesForLine(canvas, line, globalLine, y);

                // Draw auto-completion suggestion
                drawAutoSuggestion(canvas, line, globalLine, y);

                if (bracketGuideState != null) {
                    List<BracketGuideToken> guideTokens = updateBracketGuideStateForLine(line, globalLine, bracketGuideState);
                    drawBracketGuidesForLine(canvas, line, globalLine, guideTokens);
                }

                drawBracketMatchForLine(canvas, line, globalLine, bracketMatch);
            }
        }
        if (isFocused() && !hasSelection && cursorLine >= firstVisibleLine && cursorLine <= lastVisibleLine
                && (!isCodeFoldingEnabled || !isLineHiddenByFold(cursorLine))) {
            String cursorLineText = getLineTextForRender(cursorLine);
            int safeChar = Math.min(cursorChar, cursorLineText.length());
            float cursorX = measureText(cursorLineText, safeChar, cursorLine);
            float cursorY = getDrawLineTop(cursorLine);
            if (isCursorVisible) {
                caretPaint.setColor(cursorAndHandlesColor);
                caretPaint.setStrokeWidth(cursorWidth);
                canvas.drawLine(cursorX, cursorY, cursorX, cursorY + lineHeight, caretPaint);
            }
            handlePaint.setColor(cursorAndHandlesColor);
            drawTeardropHandle(canvas, cursorX, cursorY + lineHeight, handlePaint);
            cursorHandleRect.set(cursorX - handleRadius, cursorY + lineHeight, cursorX + handleRadius, cursorY + lineHeight + handleRadius * 2);
        }

        if (hasSelection) {
            handlePaint.setColor(cursorAndHandlesColor);
            if (selStartLine >= firstVisibleLine && selStartLine <= lastVisibleLine
                    && (!isCodeFoldingEnabled || !isLineHiddenByFold(selStartLine))) {
                String startLineText = getLineTextForRender(selStartLine);
                float startX = measureText(startLineText, Math.min(selStartChar, startLineText.length()), selStartLine);
                float startY = getDrawLineTop(selStartLine) + lineHeight;
                drawTeardropHandle(canvas, startX, startY, handlePaint);
                leftHandleRect.set(startX - handleRadius, startY, startX + handleRadius, startY + handleRadius * 2);
            } else {
                leftHandleRect.setEmpty();
            }
            if (selEndLine >= firstVisibleLine && selEndLine <= lastVisibleLine
                    && (!isCodeFoldingEnabled || !isLineHiddenByFold(selEndLine))) {
                String endLineText = getLineTextForRender(selEndLine);
                float endX = measureText(endLineText, Math.min(selEndChar, endLineText.length()), selEndLine);
                float endY = getDrawLineTop(selEndLine) + lineHeight;
                drawTeardropHandle(canvas, endX, endY, handlePaint);
                rightHandleRect.set(endX - handleRadius, endY, endX + handleRadius, endY + handleRadius * 2);
            } else {
                rightHandleRect.setEmpty();
            }
        }

        canvas.restore();
        // --- End of main text content drawing ---


        // --- 4. Draw overlays (popups, loading circle, etc.) ---
        if (showPopup) drawPopup(canvas);

        if (showLoadingCircle) {

            loadingCirclePaint.setColor(loadingCircleColor);
            loadingCirclePaint.setStrokeWidth(8f);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            canvas.save();
            canvas.rotate(loadingCircleRotation, centerX, centerY);
            loadingCircleRect.set(centerX - loadingCircleRadius, centerY - loadingCircleRadius,
                    centerX + loadingCircleRadius, centerY + loadingCircleRadius);
            canvas.drawArc(loadingCircleRect, 0, 270, false, loadingCirclePaint);
            canvas.restore();
        }
    }

    private Paint getPaintForChar(int lineIndex, int charIndex, String lineText) {
        List<HighlightSpan> spans = highlightCache.get(lineIndex);
        if (spans == null) {
            spans = calculateSpansForLine(lineText, lineIndex);
            highlightCache.put(lineIndex, spans);
        }
        for (HighlightSpan span : spans) {
            if (charIndex >= span.start && charIndex < span.end) {
                return span.paint;
            }
        }
        return paint;
    }

    private void drawHighlightedLine(Canvas canvas, String line, int globalLine, float y) {
        if (line.isEmpty()) {
            if (isCharAnimationEnabled && globalLine == delAnimLine && delAnimText != null && !delAnimText.isEmpty() && delAnimAlpha > 0f) {
                Paint ghostPaint = (delAnimPaint != null) ? delAnimPaint : paint;
                charAnimTmpPaint.set(ghostPaint);
                charAnimTmpPaint.setUnderlineText(false);
                int baseAlpha = ghostPaint.getAlpha();
                charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, delAnimAlpha))));
                canvas.drawText(delAnimText, 0f, y, charAnimTmpPaint);
            }
            return;
        }

        final List<UnderlineSpan> urlUnderlines = getUrlUnderlineSpansForLine(line, globalLine);

        int fadeStart = -1;
        int fadeEnd = -1;
        float fadeAlpha = 1f;
        if (isCharAnimationEnabled && globalLine == charAnimLine && charAnimEndChar > charAnimStartChar && charAnimAlpha < 1f) {
            fadeStart = Math.max(0, Math.min(charAnimStartChar, line.length()));
            fadeEnd = Math.max(0, Math.min(charAnimEndChar, line.length()));
            fadeAlpha = Math.max(0f, Math.min(1f, charAnimAlpha));
            if (fadeEnd <= fadeStart) {
                fadeStart = -1;
                fadeEnd = -1;
            }
        }

        float lineTop = getDrawLineTop(globalLine);
        float lineBottom = lineTop + lineHeight;

        if (highlightRules.isEmpty()) {
            drawTextSegmentWithFadeAndUnderlines(canvas, line, 0, line.length(), 0f, y, paint, fadeStart, fadeEnd, fadeAlpha, urlUnderlines, lineTop, lineBottom);
            if (isCharAnimationEnabled && globalLine == delAnimLine && delAnimText != null && !delAnimText.isEmpty() && delAnimAlpha > 0f) {
                int at = Math.max(0, Math.min(delAnimAtChar, line.length()));
                float x = measureText(line, at, globalLine);
                Paint ghostPaint = (delAnimPaint != null) ? delAnimPaint : paint;
                charAnimTmpPaint.set(ghostPaint);
                charAnimTmpPaint.setUnderlineText(false);
                int baseAlpha = ghostPaint.getAlpha();
                charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, delAnimAlpha))));
                canvas.drawText(delAnimText, x, y, charAnimTmpPaint);
            }
            return;
        }

        List<HighlightSpan> spans = highlightCache.get(globalLine);
        if (spans == null) {
            spans = calculateSpansForLine(line, globalLine);
            highlightCache.put(globalLine, spans);
        }

        if (spans.isEmpty()) {
            drawTextSegmentWithFadeAndUnderlines(canvas, line, 0, line.length(), 0f, y, paint, fadeStart, fadeEnd, fadeAlpha, urlUnderlines, lineTop, lineBottom);
            if (isCharAnimationEnabled && globalLine == delAnimLine && delAnimText != null && !delAnimText.isEmpty() && delAnimAlpha > 0f) {
                int at = Math.max(0, Math.min(delAnimAtChar, line.length()));
                float x = measureText(line, at, globalLine);
                Paint ghostPaint = (delAnimPaint != null) ? delAnimPaint : paint;
                charAnimTmpPaint.set(ghostPaint);
                charAnimTmpPaint.setUnderlineText(false);
                int baseAlpha = ghostPaint.getAlpha();
                charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, delAnimAlpha))));
                canvas.drawText(delAnimText, x, y, charAnimTmpPaint);
            }
            return;
        }

        float currentX = 0f;
        int lastEnd = 0;

        for (HighlightSpan span : spans) {
            if (span.start < lastEnd) continue;

            if (span.start >= line.length()) break;
            int safeSpanEnd = Math.min(span.end, line.length());

            if (span.start > lastEnd) {
                currentX += drawTextSegmentWithFadeAndUnderlines(canvas, line, lastEnd, span.start, currentX, y, paint, fadeStart, fadeEnd, fadeAlpha, urlUnderlines, lineTop, lineBottom);
            }

            currentX += drawTextSegmentWithFadeAndUnderlines(canvas, line, span.start, safeSpanEnd, currentX, y, span.paint, fadeStart, fadeEnd, fadeAlpha, urlUnderlines, lineTop, lineBottom);
            lastEnd = safeSpanEnd;
        }

        if (lastEnd < line.length()) {
            drawTextSegmentWithFadeAndUnderlines(canvas, line, lastEnd, line.length(), currentX, y, paint, fadeStart, fadeEnd, fadeAlpha, urlUnderlines, lineTop, lineBottom);
        }

        if (isCharAnimationEnabled && globalLine == delAnimLine && delAnimText != null && !delAnimText.isEmpty() && delAnimAlpha > 0f) {
            int at = Math.max(0, Math.min(delAnimAtChar, line.length()));
            float x = measureText(line, at, globalLine);
            Paint ghostPaint = (delAnimPaint != null) ? delAnimPaint : paint;
            charAnimTmpPaint.set(ghostPaint);
            charAnimTmpPaint.setUnderlineText(false);
            int baseAlpha = ghostPaint.getAlpha();
            charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, delAnimAlpha))));
            canvas.drawText(delAnimText, x, y, charAnimTmpPaint);
        }
    }

    private void drawWhitespaceGuidesForLine(Canvas canvas, String line, int globalLine, float y) {
        if (!isWhitespaceGuidesEnabled || line.isEmpty()) return;
        if (line.indexOf(' ') < 0 && line.indexOf('\t') < 0) return;

        List<HighlightSpan> syntaxSpans = getWhitespaceGuideSyntaxSpans(line, globalLine);
        boolean hasSyntaxSpans = !syntaxSpans.isEmpty();
        whitespaceDrawState.syntaxIndex = 0;

        List<HighlightSpan> visualSpans = highlightCache.get(globalLine);
        if (visualSpans == null) {
            visualSpans = calculateSpansForLine(line, globalLine);
            highlightCache.put(globalLine, visualSpans);
        }

        float currentX = 0f;
        int lastEnd = 0;

        if (!visualSpans.isEmpty()) {
            for (HighlightSpan span : visualSpans) {
                if (span.start < lastEnd) continue;
                if (span.start >= line.length()) break;

                int safeSpanEnd = Math.min(span.end, line.length());
                if (span.start > lastEnd) {
                    currentX = drawWhitespaceGuidesSegment(
                            canvas, line, lastEnd, span.start, currentX, y, paint,
                            syntaxSpans, hasSyntaxSpans, whitespaceDrawState
                    );
                }

                currentX = drawWhitespaceGuidesSegment(
                        canvas, line, span.start, safeSpanEnd, currentX, y, span.paint,
                        syntaxSpans, hasSyntaxSpans, whitespaceDrawState
                );
                lastEnd = safeSpanEnd;
            }
        }

        if (lastEnd < line.length()) {
            drawWhitespaceGuidesSegment(
                    canvas, line, lastEnd, line.length(), currentX, y, paint,
                    syntaxSpans, hasSyntaxSpans, whitespaceDrawState
            );
        }
    }

    private float drawTextSegmentWithFade(
            Canvas canvas,
            String line,
            int start,
            int end,
            float x,
            float y,
            Paint segmentPaint,
            int fadeStart,
            int fadeEnd,
            float fadeAlpha
    ) {
        if (start >= end) return 0f;
        final int spaceScale = getVisualSpaceScale();
        if (spaceScale > 1) {
            boolean hasFade = fadeStart >= 0 && fadeEnd > fadeStart && fadeAlpha < 1f;
            if (!hasFade || end <= fadeStart || start >= fadeEnd) {
                return drawTextSegmentWithVisualSpaces(canvas, line, start, end, x, y, segmentPaint, 1f);
            }

            float currentX = x;

            int beforeEnd = Math.min(end, fadeStart);
            if (start < beforeEnd) {
                currentX += drawTextSegmentWithVisualSpaces(canvas, line, start, beforeEnd, currentX, y, segmentPaint, 1f);
            }

            int fadeSegStart = Math.max(start, fadeStart);
            int fadeSegEnd = Math.min(end, fadeEnd);
            if (fadeSegStart < fadeSegEnd) {
                currentX += drawTextSegmentWithVisualSpaces(canvas, line, fadeSegStart, fadeSegEnd, currentX, y, segmentPaint, fadeAlpha);
            }

            int afterStart = Math.max(start, fadeEnd);
            if (afterStart < end) {
                currentX += drawTextSegmentWithVisualSpaces(canvas, line, afterStart, end, currentX, y, segmentPaint, 1f);
            }

            return currentX - x;
        }
        boolean hasFade = fadeStart >= 0 && fadeEnd > fadeStart && fadeAlpha < 1f;
        if (!hasFade || end <= fadeStart || start >= fadeEnd) {
            canvas.drawText(line, start, end, x, y, segmentPaint);
            return segmentPaint.measureText(line, start, end);
        }

        float currentX = x;

        int beforeEnd = Math.min(end, fadeStart);
        if (start < beforeEnd) {
            canvas.drawText(line, start, beforeEnd, currentX, y, segmentPaint);
            currentX += segmentPaint.measureText(line, start, beforeEnd);
        }

        int fadeSegStart = Math.max(start, fadeStart);
        int fadeSegEnd = Math.min(end, fadeEnd);
        if (fadeSegStart < fadeSegEnd) {
            charAnimTmpPaint.set(segmentPaint);
            int baseAlpha = segmentPaint.getAlpha();
            charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, fadeAlpha))));
            canvas.drawText(line, fadeSegStart, fadeSegEnd, currentX, y, charAnimTmpPaint);
            currentX += segmentPaint.measureText(line, fadeSegStart, fadeSegEnd);
        }

        int afterStart = Math.max(start, fadeEnd);
        if (afterStart < end) {
            canvas.drawText(line, afterStart, end, currentX, y, segmentPaint);
            currentX += segmentPaint.measureText(line, afterStart, end);
        }

        return currentX - x;
    }

    private float drawTextSegmentWithFadeAndUnderlines(
            Canvas canvas,
            String line,
            int start,
            int end,
            float x,
            float y,
            Paint segmentPaint,
            int fadeStart,
            int fadeEnd,
            float fadeAlpha,
            @Nullable List<UnderlineSpan> underlines,
            float lineTop,
            float lineBottom
    ) {
        if (start >= end) return 0f;
        if (underlines == null || underlines.isEmpty() || urlUnderlinePattern == null || !isUrlUnderliningEnabled) {
            return drawTextSegmentWithFade(canvas, line, start, end, x, y, segmentPaint, fadeStart, fadeEnd, fadeAlpha);
        }

        float currentX = x;
        int pos = start;

        for (UnderlineSpan span : underlines) {
            if (span.end <= pos) continue;
            if (span.start >= end) break;

            int plainEnd = Math.min(end, Math.max(pos, span.start));
            if (pos < plainEnd) {
                currentX += drawTextSegmentWithFade(canvas, line, pos, plainEnd, currentX, y, segmentPaint, fadeStart, fadeEnd, fadeAlpha);
                pos = plainEnd;
            }

            int underlineStart = Math.max(pos, span.start);
            int underlineEnd = Math.min(end, span.end);
            if (underlineStart < underlineEnd) {
                float underlineXStart = currentX;
                currentX += drawTextSegmentWithFade(canvas, line, underlineStart, underlineEnd, currentX, y, segmentPaint, fadeStart, fadeEnd, fadeAlpha);
                drawUnderlineSegmentWithFade(
                        canvas,
                        line,
                        underlineStart,
                        underlineEnd,
                        underlineXStart,
                        y,
                        lineTop,
                        lineBottom,
                        segmentPaint,
                        fadeStart,
                        fadeEnd,
                        fadeAlpha
                );
                pos = underlineEnd;
            }
        }

        if (pos < end) {
            currentX += drawTextSegmentWithFade(canvas, line, pos, end, currentX, y, segmentPaint, fadeStart, fadeEnd, fadeAlpha);
        }

        return currentX - x;
    }

    private void drawUnderlineSegmentWithFade(
            Canvas canvas,
            String line,
            int start,
            int end,
            float x,
            float baselineY,
            float lineTop,
            float lineBottom,
            Paint textPaint,
            int fadeStart,
            int fadeEnd,
            float fadeAlpha
    ) {
        if (start >= end) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float underlineY = baselineY + (fm.descent * 0.5f);
        underlineY = Math.max(lineTop + 1f, Math.min(underlineY, lineBottom - 2f));

        float thickness = Math.max(1f, textPaint.getTextSize() / 18f);
        thickness = Math.min(thickness, Math.max(1f, (lineBottom - lineTop) / 8f));

        urlUnderlineTmpPaint.set(textPaint);
        urlUnderlineTmpPaint.setStyle(Paint.Style.STROKE);
        urlUnderlineTmpPaint.setStrokeWidth(thickness);
        urlUnderlineTmpPaint.setUnderlineText(false);

        boolean hasFade = fadeStart >= 0 && fadeEnd > fadeStart && fadeAlpha < 1f;
        if (!hasFade || end <= fadeStart || start >= fadeEnd) {
            float w = measureTextWithVisualSpaces(line, start, end, textPaint);
            if (w > 0f) canvas.drawLine(x, underlineY, x + w, underlineY, urlUnderlineTmpPaint);
            return;
        }

        float currentX = x;
        int baseAlpha = textPaint.getAlpha();

        int beforeEnd = Math.min(end, fadeStart);
        if (start < beforeEnd) {
            urlUnderlineTmpPaint.setAlpha(baseAlpha);
            float w = measureTextWithVisualSpaces(line, start, beforeEnd, textPaint);
            if (w > 0f) canvas.drawLine(currentX, underlineY, currentX + w, underlineY, urlUnderlineTmpPaint);
            currentX += w;
        }

        int fadeSegStart = Math.max(start, fadeStart);
        int fadeSegEnd = Math.min(end, fadeEnd);
        if (fadeSegStart < fadeSegEnd) {
            urlUnderlineTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, fadeAlpha))));
            float w = measureTextWithVisualSpaces(line, fadeSegStart, fadeSegEnd, textPaint);
            if (w > 0f) canvas.drawLine(currentX, underlineY, currentX + w, underlineY, urlUnderlineTmpPaint);
            currentX += w;
        }

        int afterStart = Math.max(start, fadeEnd);
        if (afterStart < end) {
            urlUnderlineTmpPaint.setAlpha(baseAlpha);
            float w = measureTextWithVisualSpaces(line, afterStart, end, textPaint);
            if (w > 0f) canvas.drawLine(currentX, underlineY, currentX + w, underlineY, urlUnderlineTmpPaint);
        }
    }

    @Nullable
    private List<UnderlineSpan> getUrlUnderlineSpansForLine(String line, int globalLine) {
        if (!isUrlUnderliningEnabled || urlUnderlinePattern == null) return null;
        List<UnderlineSpan> cached = urlUnderlineCache.get(globalLine);
        if (cached != null) return cached;

        ArrayList<UnderlineSpan> spans = new ArrayList<>();
        Matcher matcher = urlUnderlinePattern.matcher(line);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            end = trimUrlUnderlineEnd(line, start, end);
            if (end > start) {
                spans.add(new UnderlineSpan(start, end));
            }
        }
        urlUnderlineCache.put(globalLine, spans);
        return spans;
    }

    private static int trimUrlUnderlineEnd(String line, int start, int end) {
        int e = Math.min(end, line.length());
        while (e > start) {
            char c = line.charAt(e - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
                    || c == ')' || c == ']' || c == '}' || c == '>' || c == '"' || c == '\'') {
                e--;
                continue;
            }
            break;
        }
        return e;
    }

    private int getVisualSpaceScale() {
        if (!isWhitespaceGuidesEnabled) return 1;
        return Math.max(1, whitespaceGuideSpaceStep);
    }

    private float getVisualSpaceWidth(Paint p) {
        return p.measureText(" ") * getVisualSpaceScale();
    }

    private float getVisualTabWidth(Paint p) {
        // Treat tab as a fixed number of spaces, scaled the same way.
        return getVisualSpaceWidth(p) * DEFAULT_TAB_SIZE_SPACES;
    }

    private float getCharAdvanceWidth(char c, float measuredWidth, Paint p) {
        if (c == ' ') {
            return measuredWidth * getVisualSpaceScale();
        }
        if (c == '\t') {
            return getVisualTabWidth(p);
        }
        return measuredWidth;
    }

    private float measureTextWithVisualSpaces(String text, int start, int end, Paint p) {
        if (text == null) return 0f;
        start = Math.max(0, Math.min(start, text.length()));
        end = Math.max(start, Math.min(end, text.length()));
        if (start >= end) return 0f;

        int scale = getVisualSpaceScale();
        if (scale == 1 && text.indexOf('\t', start) < 0) {
            return p.measureText(text, start, end);
        }

        int len = end - start;
        if (measureWidthBuffer == null || measureWidthBuffer.length < len) {
            measureWidthBuffer = new float[len];
        }
        p.getTextWidths(text, start, end, measureWidthBuffer);
        float total = 0f;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(start + i);
            total += getCharAdvanceWidth(c, measureWidthBuffer[i], p);
        }
        return total;
    }

    private float drawTextSegmentWithVisualSpaces(
            Canvas canvas,
            String line,
            int start,
            int end,
            float x,
            float y,
            Paint segmentPaint,
            float alphaMultiplier
    ) {
        if (start >= end) return 0f;

        Paint drawPaint = segmentPaint;
        if (alphaMultiplier < 1f) {
            charAnimTmpPaint.set(segmentPaint);
            int baseAlpha = segmentPaint.getAlpha();
            charAnimTmpPaint.setAlpha((int) (baseAlpha * Math.max(0f, Math.min(1f, alphaMultiplier))));
            drawPaint = charAnimTmpPaint;
        }

        int len = end - start;
        if (measureWidthBuffer == null || measureWidthBuffer.length < len) {
            measureWidthBuffer = new float[len];
        }
        segmentPaint.getTextWidths(line, start, end, measureWidthBuffer);

        float currentX = x;
        int runStart = start;
        float runX = currentX;

        for (int i = 0; i < len; i++) {
            int charIndex = start + i;
            char c = line.charAt(charIndex);
            float adv = getCharAdvanceWidth(c, measureWidthBuffer[i], segmentPaint);
            boolean isVirtualSpace = (c == ' ' || c == '\t');
            if (isVirtualSpace) {
                if (runStart < charIndex) {
                    canvas.drawText(line, runStart, charIndex, runX, y, drawPaint);
                }
                currentX += adv;
                runStart = charIndex + 1;
                runX = currentX;
            } else {
                currentX += adv;
            }
        }

        if (runStart < end) {
            canvas.drawText(line, runStart, end, runX, y, drawPaint);
        }
        return currentX - x;
    }

    private List<HighlightSpan> calculateSyntaxSpansForLine(String line, int globalLine) {
        if (line.isEmpty()) {
            return Collections.emptyList();
        }

        HighlightLineState startState = getLineStateAtStart(globalLine);
        LineParseResult parseResult = parseLineForSyntax(
                line,
                startState.inBlockComment,
                startState.stringState,
                whitespaceStringRule,
                whitespaceCommentRule,
                true
        );

        if (globalLine >= windowStartLine
                && globalLine < windowStartLine + linesWindow.size()) {
            if (isBlockCommentsEnabled) {
                blockCommentEndStateCache.put(globalLine, parseResult.endsInBlockComment);
            }
            stringEndStateCache.put(globalLine, parseResult.endsInStringState);
        }

        List<HighlightSpan> spans = parseResult.spans;
        if (spans.size() > 1) {
            Collections.sort(spans, (s1, s2) -> Integer.compare(s1.start, s2.start));
        }
        return spans;
    }

    private List<HighlightSpan> getWhitespaceGuideSyntaxSpans(String line, int globalLine) {
        HighlightRule stringRule = stringHighlightRule;
        HighlightRule commentRule = blockCommentHighlightRule;
        if (stringRule == null && commentRule == null) {
            return calculateSyntaxSpansForLine(line, globalLine);
        }

        List<HighlightSpan> spans = highlightCache.get(globalLine);
        if (spans == null) {
            spans = calculateSpansForLine(line, globalLine);
            highlightCache.put(globalLine, spans);
        }
        if (spans.isEmpty()) return Collections.emptyList();

        Paint stringPaint = (stringRule != null) ? stringRule.paint : null;
        Paint commentPaint = (commentRule != null) ? commentRule.paint : null;
        if (stringPaint == null && commentPaint == null) return Collections.emptyList();

        ArrayList<HighlightSpan> syntaxSpans = null;
        for (HighlightSpan span : spans) {
            if (span.paint == stringPaint || span.paint == commentPaint) {
                if (syntaxSpans == null) syntaxSpans = new ArrayList<>();
                syntaxSpans.add(span);
            }
        }
        return syntaxSpans != null ? syntaxSpans : Collections.emptyList();
    }

    private float drawWhitespaceGuidesSegment(
            Canvas canvas,
            String line,
            int start,
            int end,
            float x,
            float y,
            Paint segmentPaint,
            List<HighlightSpan> syntaxSpans,
            boolean hasSyntaxSpans,
            WhitespaceDrawState state
    ) {
        if (start >= end) return x;
        int segLen = end - start;
        if (whitespaceWidthBuffer == null || whitespaceWidthBuffer.length < segLen) {
            whitespaceWidthBuffer = new float[segLen];
        }
        segmentPaint.getTextWidths(line, start, end, whitespaceWidthBuffer);

        final int spaceScale = getVisualSpaceScale();
        float currentX = x;
        int localSyntaxIndex = hasSyntaxSpans ? state.syntaxIndex : 0;
        HighlightSpan activeSyntax = hasSyntaxSpans && localSyntaxIndex < syntaxSpans.size()
                ? syntaxSpans.get(localSyntaxIndex)
                : null;

        for (int i = 0; i < segLen; i++) {
            int charIndex = start + i;
            while (activeSyntax != null && charIndex >= activeSyntax.end) {
                localSyntaxIndex++;
                activeSyntax = localSyntaxIndex < syntaxSpans.size() ? syntaxSpans.get(localSyntaxIndex) : null;
            }

            boolean isInSyntaxSpan = activeSyntax != null && charIndex >= activeSyntax.start && charIndex < activeSyntax.end;
            char c = line.charAt(charIndex);
            if (!isInSyntaxSpan && c == ' ') {
                int runStart = i;
                int runEnd = i + 1;
                float runWidth = whitespaceWidthBuffer[i];
                for (int j = i + 1; j < segLen; j++) {
                    int runCharIndex = start + j;
                    while (activeSyntax != null && runCharIndex >= activeSyntax.end) {
                        localSyntaxIndex++;
                        activeSyntax = localSyntaxIndex < syntaxSpans.size() ? syntaxSpans.get(localSyntaxIndex) : null;
                    }
                    boolean inSyntax = activeSyntax != null
                            && runCharIndex >= activeSyntax.start
                            && runCharIndex < activeSyntax.end;
                    if (inSyntax || line.charAt(runCharIndex) != ' ') break;
                    runWidth += whitespaceWidthBuffer[j];
                    runEnd = j + 1;
                }

                int spacesInRun = runEnd - runStart;
                float runCursorX = currentX;
                for (int k = 0; k < spacesInRun; k++) {
                    float visualWidth = whitespaceWidthBuffer[runStart + k] * spaceScale;
                    float glyphX = runCursorX + Math.max(0f, (visualWidth - whitespaceGuideSpaceWidth) * 0.5f);
                    canvas.drawText(WHITESPACE_GUIDE_SPACE, glyphX, y, whitespaceGuidePaint);
                    runCursorX += visualWidth;
                }

                currentX += runWidth * spaceScale;
                i = runEnd - 1;
                continue;
            }

            if (!isInSyntaxSpan && c == '\t') {
                float charWidth = getVisualTabWidth(segmentPaint);
                float glyphX = currentX + Math.max(0f, (charWidth - whitespaceGuideTabWidth) * 0.5f);
                canvas.drawText(WHITESPACE_GUIDE_TAB, glyphX, y, whitespaceGuidePaint);
                currentX += charWidth;
                continue;
            }
            currentX += whitespaceWidthBuffer[i];
        }

        if (hasSyntaxSpans) {
            state.syntaxIndex = localSyntaxIndex;
        }
        return currentX;
    }

    private List<HighlightSpan> calculateSpansForLine(String line, int globalLine) {
        List<HighlightSpan> spans = new ArrayList<>();
        if (highlightRules.isEmpty()) {
            return spans;
        }

        HighlightRule stringRule = stringHighlightRule;
        HighlightRule blockCommentRule = blockCommentHighlightRule;

        if (stringRule != null || blockCommentRule != null) {
            HighlightLineState startState = getLineStateAtStart(globalLine);
            LineParseResult parseResult = parseLineForSyntax(
                    line,
                    startState.inBlockComment,
                    startState.stringState,
                    stringRule,
                    blockCommentRule,
                    true
            );
            spans.addAll(parseResult.spans);

            if (globalLine >= windowStartLine
                    && globalLine < windowStartLine + linesWindow.size()) {
                if (isBlockCommentsEnabled) {
                    blockCommentEndStateCache.put(globalLine, parseResult.endsInBlockComment);
                }
                stringEndStateCache.put(globalLine, parseResult.endsInStringState);
            }
        }

        if (!regexHighlightRules.isEmpty() && !line.isEmpty()) {
            for (HighlightRule rule : regexHighlightRules) {
                Matcher matcher = rule.pattern.matcher(line);
                while (matcher.find()) {
                    if (matcher.start() == matcher.end()) continue;
                    HighlightSpan span = new HighlightSpan(matcher.start(), matcher.end(), rule.paint);
                    if (hasOverlap(span, spans)) continue;
                    spans.add(span);
                }
            }
        }

        if (spans.size() > 1) {
            Collections.sort(spans, (s1, s2) -> Integer.compare(s1.start, s2.start));
        }
        return spans;
    }

    private LineParseResult parseLineForSyntax(
            String line,
            boolean inBlockComment,
            int stringState,
            HighlightRule stringRule,
            HighlightRule blockCommentRule,
            boolean collectSpans
    ) {
        List<HighlightSpan> spans = new ArrayList<>();
        int length = line.length();
        int i = 0;
        if (!isBlockCommentsEnabled) {
            inBlockComment = false;
        }
        if (stringState == STRING_STATE_BACKTICK && !isBacktickStringsEnabled) {
            stringState = 0;
        }
        if (stringState == STRING_STATE_TRIPLE && !isTripleQuoteStringsEnabled) {
            stringState = 0;
        }
        if (stringState != 0 && !isMultiLineStringsEnabled && stringState != STRING_STATE_TRIPLE) {
            stringState = 0;
        }

        while (i < length) {
            if (inBlockComment) {
                int end = findBlockCommentEnd(line, i);
                if (end < 0) {
                    if (collectSpans && blockCommentRule != null && isBlockCommentsEnabled && length > 0) {
                        spans.add(new HighlightSpan(0, length, blockCommentRule.paint));
                    }
                    return new LineParseResult(spans, true, 0);
                }
                if (collectSpans && blockCommentRule != null && isBlockCommentsEnabled) {
                    spans.add(new HighlightSpan(0, end + 2, blockCommentRule.paint));
                }
                i = end + 2;
                inBlockComment = false;
                continue;
            }

            if (stringState != 0) {
                StringEndResult endResult = findStringEndForState(line, i, stringState);
                if (endResult.found) {
                    if (collectSpans && stringRule != null) {
                        spans.add(new HighlightSpan(0, endResult.endIndex, stringRule.paint));
                    }
                    i = endResult.endIndex;
                    stringState = 0;
                    continue;
                }
                if (collectSpans && stringRule != null && length > 0) {
                    spans.add(new HighlightSpan(0, length, stringRule.paint));
                }
                return new LineParseResult(spans, false, stringState);
            }

            if (isLineCommentStart(line, i)) {
                if (collectSpans && length > i) {
                    Paint commentPaint = (blockCommentRule != null) ? blockCommentRule.paint : paint;
                    spans.add(new HighlightSpan(i, length, commentPaint));
                }
                return new LineParseResult(spans, false, 0);
            }

            char c = line.charAt(i);
            if (isTripleQuoteStart(line, i) && !isEscaped(line, i)) {
                int end = findTripleQuoteEnd(line, i + 3);
                if (end >= 0) {
                    if (collectSpans && stringRule != null) {
                        spans.add(new HighlightSpan(i, end + 3, stringRule.paint));
                    }
                    i = end + 3;
                    continue;
                }
                if (isTripleQuoteStringsEnabled) {
                    if (collectSpans && stringRule != null && length > 0) {
                        spans.add(new HighlightSpan(i, length, stringRule.paint));
                    }
                    return new LineParseResult(spans, false, STRING_STATE_TRIPLE);
                }
            }

            if (isStringDelimiter(c) && !isEscaped(line, i)) {
                int end = findStringEnd(line, i + 1, c);
                if (end >= 0) {
                    if (collectSpans && stringRule != null) {
                        spans.add(new HighlightSpan(i, end + 1, stringRule.paint));
                    }
                    i = end + 1;
                    continue;
                }
                if (isMultiLineStringsEnabled) {
                    if (collectSpans && stringRule != null && length > 0) {
                        spans.add(new HighlightSpan(i, length, stringRule.paint));
                    }
                    return new LineParseResult(spans, false, getStringStateForDelimiter(c));
                }
            }

            if (isBlockCommentsEnabled
                    && c == '/'
                    && i + 1 < length
                    && line.charAt(i + 1) == '*'
                    && !isTokenEscaped(line, i)) {
                int end = findBlockCommentEnd(line, i + 2);
                if (end < 0) {
                    if (collectSpans && blockCommentRule != null && length > 0) {
                        spans.add(new HighlightSpan(i, length, blockCommentRule.paint));
                    }
                    return new LineParseResult(spans, true, 0);
                }
                if (collectSpans && blockCommentRule != null) {
                    spans.add(new HighlightSpan(i, end + 2, blockCommentRule.paint));
                }
                i = end + 2;
                continue;
            }

            i++;
        }

        return new LineParseResult(spans, inBlockComment, stringState);
    }

    private HighlightLineState getLineStateAtStart(int globalLine) {
        if (globalLine <= windowStartLine) return new HighlightLineState(false, 0);
        int windowEnd = windowStartLine + linesWindow.size() - 1;
        if (globalLine > windowEnd) return new HighlightLineState(false, 0);

        Boolean cachedBlockPrev = blockCommentEndStateCache.get(globalLine - 1);
        Integer cachedStringPrev = stringEndStateCache.get(globalLine - 1);
        if (cachedBlockPrev != null && cachedStringPrev != null) {
            return new HighlightLineState(cachedBlockPrev, cachedStringPrev);
        }

        boolean inBlock = false;
        int stringState = 0;
        for (int line = windowStartLine; line < globalLine; line++) {
            Boolean cachedBlock = blockCommentEndStateCache.get(line);
            Integer cachedString = stringEndStateCache.get(line);
            if (cachedBlock != null && cachedString != null) {
                inBlock = cachedBlock;
                stringState = cachedString;
                continue;
            }
            String lineText = getLineFromWindowLocal(line - windowStartLine);
            if (lineText == null) lineText = "";
            LineParseResult result = parseLineForSyntax(
                    lineText,
                    inBlock,
                    stringState,
                    null,
                    null,
                    false
            );
            inBlock = result.endsInBlockComment;
            stringState = result.endsInStringState;
            blockCommentEndStateCache.put(line, inBlock);
            stringEndStateCache.put(line, stringState);
        }
        return new HighlightLineState(inBlock, stringState);
    }

    private static boolean hasOverlap(HighlightSpan span, List<HighlightSpan> spans) {
        for (HighlightSpan other : spans) {
            if (span.start < other.end && other.start < span.end) {
                return true;
            }
        }
        return false;
    }

    private boolean isStringDelimiter(char c) {
        if (c == '"') return true;
        if (c == '\'') return true;
        return c == '`' && isBacktickStringsEnabled;
    }

    private static boolean isTokenEscaped(String line, int index) {
        if (isEscaped(line, index)) return true;
        int next = index + 1;
        return next < line.length() && isEscaped(line, next);
    }

    private static boolean isEscaped(String line, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0; i--) {
            if (line.charAt(i) != '\\') break;
            backslashes++;
        }
        return (backslashes % 2) == 1;
    }

    private static int findStringEnd(String line, int start, char delimiter) {
        for (int i = start; i < line.length(); i++) {
            if (line.charAt(i) == delimiter && !isEscaped(line, i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isTripleQuoteStart(String line, int index) {
        if (!isTripleQuoteStringsEnabled) return false;
        if (index + 2 >= line.length()) return false;
        return line.charAt(index) == '"'
                && line.charAt(index + 1) == '"'
                && line.charAt(index + 2) == '"';
    }

    private static int findTripleQuoteEnd(String line, int start) {
        for (int i = start; i + 2 < line.length(); i++) {
            if (line.charAt(i) == '"'
                    && line.charAt(i + 1) == '"'
                    && line.charAt(i + 2) == '"'
                    && !isEscaped(line, i)) {
                return i;
            }
        }
        return -1;
    }

    private static final int STRING_STATE_DOUBLE = 1;
    private static final int STRING_STATE_SINGLE = 2;
    private static final int STRING_STATE_BACKTICK = 3;
    private static final int STRING_STATE_TRIPLE = 4;

    private int getStringStateForDelimiter(char delimiter) {
        if (delimiter == '"') return STRING_STATE_DOUBLE;
        if (delimiter == '\'') return STRING_STATE_SINGLE;
        return STRING_STATE_BACKTICK;
    }

    private StringEndResult findStringEndForState(String line, int start, int state) {
        if (state == STRING_STATE_TRIPLE) {
            int end = findTripleQuoteEnd(line, start);
            return new StringEndResult(end >= 0, end >= 0 ? end + 3 : start);
        }
        char delimiter = '"';
        if (state == STRING_STATE_SINGLE) delimiter = '\'';
        if (state == STRING_STATE_BACKTICK) delimiter = '`';
        int end = findStringEnd(line, start, delimiter);
        return new StringEndResult(end >= 0, end >= 0 ? end + 1 : start);
    }

    private static class StringEndResult {
        final boolean found;
        final int endIndex;
        StringEndResult(boolean found, int endIndex) {
            this.found = found;
            this.endIndex = endIndex;
        }
    }

    private static int findBlockCommentEnd(String line, int start) {
        for (int i = start; i + 1 < line.length(); i++) {
            if (line.charAt(i) == '*' && line.charAt(i + 1) == '/' && !isTokenEscaped(line, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBracketChar(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    private static boolean isOpeningBracket(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    private static boolean isClosingBracket(char c) {
        return c == ')' || c == ']' || c == '}';
    }

    private static char matchingBracket(char c) {
        switch (c) {
            case '(': return ')';
            case ')': return '(';
            case '[': return ']';
            case ']': return '[';
            case '{': return '}';
            case '}': return '{';
            default: return 0;
        }
    }

    private boolean isLineCommentStart(String line, int index) {
        if (index < 0 || index >= line.length()) return false;
        if (line.charAt(index) == '#' && !isTokenEscaped(line, index)) return true;
        if (line.charAt(index) == '/'
                && index + 1 < line.length()
                && line.charAt(index + 1) == '/'
                && !isTokenEscaped(line, index)) {
            return true;
        }
        return false;
    }

    private BracketMatch findBracketMatchInVisible(
            int firstVisibleLine,
            int lastVisibleLine,
            java.util.HashMap<Integer, String> directLines
    ) {
        if (!isBracketMatchingEnabled) return null;
        if (cursorLine < firstVisibleLine || cursorLine > lastVisibleLine) return null;

        String cursorLineText = getLineTextForRenderWithDirect(cursorLine, directLines);
        if (cursorLineText == null) return null;

        int targetIndex = -1;
        char targetChar = 0;
        if (cursorChar > 0 && cursorChar - 1 < cursorLineText.length()) {
            char c = cursorLineText.charAt(cursorChar - 1);
            if (isBracketChar(c)) {
                targetIndex = cursorChar - 1;
                targetChar = c;
            }
        }
        if (targetIndex < 0 && cursorChar < cursorLineText.length()) {
            char c = cursorLineText.charAt(cursorChar);
            if (isBracketChar(c)) {
                targetIndex = cursorChar;
                targetChar = c;
            }
        }
        if (targetIndex < 0) return null;

        HighlightLineState startState = getLineStateAtStart(firstVisibleLine);
        boolean inBlockComment = startState.inBlockComment && isBlockCommentsEnabled;
        int stringState = startState.stringState;
        if (!isBlockCommentsEnabled) inBlockComment = false;
        if (!isMultiLineStringsEnabled && stringState != STRING_STATE_TRIPLE) stringState = 0;
        if (!isBacktickStringsEnabled && stringState == STRING_STATE_BACKTICK) stringState = 0;
        if (!isTripleQuoteStringsEnabled && stringState == STRING_STATE_TRIPLE) stringState = 0;

        java.util.ArrayDeque<BracketToken> stack = new java.util.ArrayDeque<>();

        for (int line = firstVisibleLine; line <= lastVisibleLine; line++) {
            String text = getLineTextForRenderWithDirect(line, directLines);
            if (text == null) text = "";
            int len = text.length();
            int i = 0;
            boolean inLineComment = false;

            while (i < len) {
                if (inLineComment) break;

                if (inBlockComment) {
                    int end = findBlockCommentEnd(text, i);
                    int endPos = (end < 0) ? len : end + 2;
                    if (line == cursorLine && targetIndex >= i && targetIndex < endPos) return null;
                    if (end < 0) break;
                    i = end + 2;
                    inBlockComment = false;
                    continue;
                }

                if (stringState != 0) {
                    StringEndResult endResult = findStringEndForState(text, i, stringState);
                    int endPos = endResult.found ? endResult.endIndex : len;
                    if (line == cursorLine && targetIndex >= i && targetIndex < endPos) return null;
                    if (!endResult.found) break;
                    i = endResult.endIndex;
                    stringState = 0;
                    continue;
                }

                if (isLineCommentStart(text, i)) {
                    if (line == cursorLine && targetIndex >= i) return null;
                    inLineComment = true;
                    break;
                }

                if (isBlockCommentsEnabled
                        && i + 1 < len
                        && text.charAt(i) == '/'
                        && text.charAt(i + 1) == '*'
                        && !isTokenEscaped(text, i)) {
                    int end = findBlockCommentEnd(text, i + 2);
                    int endPos = (end < 0) ? len : end + 2;
                    if (line == cursorLine && targetIndex >= i && targetIndex < endPos) return null;
                    if (end < 0) {
                        inBlockComment = true;
                        break;
                    }
                    i = end + 2;
                    continue;
                }

                if (isTripleQuoteStart(text, i) && !isEscaped(text, i)) {
                    int end = findTripleQuoteEnd(text, i + 3);
                    int endPos = end >= 0 ? end + 3 : len;
                    if (line == cursorLine && targetIndex >= i && targetIndex < endPos) return null;
                    if (end < 0) {
                        if (isTripleQuoteStringsEnabled) {
                            stringState = STRING_STATE_TRIPLE;
                        }
                        break;
                    }
                    i = end + 3;
                    continue;
                }

                char c = text.charAt(i);
                if (isStringDelimiter(c) && !isEscaped(text, i)) {
                    int end = findStringEnd(text, i + 1, c);
                    int endPos = end >= 0 ? end + 1 : len;
                    if (line == cursorLine && targetIndex >= i && targetIndex < endPos) return null;
                    if (end < 0) {
                        if (isMultiLineStringsEnabled) {
                            stringState = getStringStateForDelimiter(c);
                        }
                        break;
                    }
                    i = end + 1;
                    continue;
                }

                if (isBracketChar(c) && !isEscaped(text, i)) {
                    BracketToken token = new BracketToken(line, i, c);
                    if (isOpeningBracket(c)) {
                        stack.push(token);
                    } else if (isClosingBracket(c)) {
                        if (!stack.isEmpty() && stack.peek().bracket == matchingBracket(c)) {
                            BracketToken open = stack.pop();
                            if (line == cursorLine && i == targetIndex) {
                                return new BracketMatch(open.line, open.ch, line, i);
                            }
                            if (open.line == cursorLine && open.ch == targetIndex) {
                                return new BracketMatch(open.line, open.ch, line, i);
                            }
                        }
                    }
                }

                i++;
            }
        }
        return new BracketMatch(cursorLine, targetIndex, cursorLine, targetIndex);
    }

    private void drawBracketMatchForLine(
            Canvas canvas,
            String line,
            int globalLine,
            BracketMatch match
    ) {
        if (match == null) return;
        if (globalLine != match.openLine && globalLine != match.closeLine) return;
        if (line == null || line.isEmpty()) return;

        if (match.openLine == match.closeLine) {
            if (match.openChar == match.closeChar) {
                drawBracketBox(canvas, line, globalLine, match.openChar);
                return;
            }

            // If the matching brackets are adjacent (e.g. "{}" or "[]"), draw a single box to avoid
            // the seam line in the middle.
            if (Math.abs(match.openChar - match.closeChar) == 1) {
                int leftIndex = Math.min(match.openChar, match.closeChar);
                int rightIndex = Math.max(match.openChar, match.closeChar);
                drawBracketBoxRange(canvas, line, globalLine, leftIndex, rightIndex);
            } else {
                drawBracketBox(canvas, line, globalLine, match.openChar);
                drawBracketBox(canvas, line, globalLine, match.closeChar);
            }
            return;
        }

        int index = (globalLine == match.openLine) ? match.openChar : match.closeChar;
        drawBracketBox(canvas, line, globalLine, index);
    }

    private void drawBracketBox(Canvas canvas, String line, int globalLine, int index) {
        if (index < 0 || index >= line.length()) return;

        float left = measureText(line, index, globalLine);
        float right = measureText(line, index + 1, globalLine);
        if (right <= left) right = left + measureTextWithVisualSpaces(line, index, index + 1, paint);

        drawBracketBoxRect(canvas, globalLine, left, right);
    }

    private void drawBracketBoxRange(Canvas canvas, String line, int globalLine, int startIndex, int endIndex) {
        if (startIndex < 0 || endIndex < 0) return;
        if (startIndex >= line.length()) return;
        if (endIndex >= line.length()) endIndex = line.length() - 1;
        if (endIndex < startIndex) return;

        float left = measureText(line, startIndex, globalLine);
        float right = measureText(line, endIndex + 1, globalLine);
        if (right <= left) right = left + measureTextWithVisualSpaces(line, startIndex, endIndex + 1, paint);
        drawBracketBoxRect(canvas, globalLine, left, right);
    }

    private void drawBracketBoxRect(Canvas canvas, int globalLine, float left, float right) {
        final float padding = 1f;
        final float top = getDrawLineTop(globalLine) + padding;
        final float bottom = top + lineHeight - (padding * 2f);

        float l = left - padding;
        float r = right + padding;
        if (r <= l) return;

        bracketMatchRect.set(l, top, r, bottom);
        float radius = Math.max(2f, bracketMatchStrokeWidth + 1f);
        canvas.drawRoundRect(bracketMatchRect, radius, radius, bracketMatchPaint);
    }

    private List<BracketGuideToken> updateBracketGuideStateForLine(String line, int globalLine, BracketGuideState state) {
        if (line == null) line = "";
        int length = line.length();
        int firstNonSpace = getFirstNonSpaceIndex(line);
        // Draw guides based on the stack at the *start* of the line.
        // This avoids incorrectly removing guides when a block opens and closes on the same line (e.g. "if {}"),
        // and keeps the guide visible on the closing-brace line.
        List<BracketGuideToken> tokensToDraw = getGuideTokensFromStack(state.stack);

        int i = 0;
        boolean inLineComment = false;

        while (i < length) {
            if (inLineComment) break;

            if (state.inBlockComment) {
                int end = findBlockCommentEnd(line, i);
                if (end < 0) return tokensToDraw;
                i = end + 2;
                state.inBlockComment = false;
                continue;
            }

            if (state.stringState != 0) {
                StringEndResult endResult = findStringEndForState(line, i, state.stringState);
                if (!endResult.found) return tokensToDraw;
                i = endResult.endIndex;
                state.stringState = 0;
                continue;
            }

            if (isLineCommentStart(line, i)) {
                inLineComment = true;
                break;
            }

            if (isBlockCommentsEnabled
                    && i + 1 < length
                    && line.charAt(i) == '/'
                    && line.charAt(i + 1) == '*'
                    && !isTokenEscaped(line, i)) {
                int end = findBlockCommentEnd(line, i + 2);
                if (end < 0) {
                    state.inBlockComment = true;
                    return tokensToDraw;
                }
                i = end + 2;
                continue;
            }

            if (isTripleQuoteStart(line, i) && !isEscaped(line, i)) {
                int end = findTripleQuoteEnd(line, i + 3);
                if (end < 0) {
                    if (isTripleQuoteStringsEnabled) {
                        state.stringState = STRING_STATE_TRIPLE;
                    }
                    return tokensToDraw;
                }
                i = end + 3;
                continue;
            }

            char c = line.charAt(i);
            if (isStringDelimiter(c) && !isEscaped(line, i)) {
                int end = findStringEnd(line, i + 1, c);
                if (end < 0) {
                    if (isMultiLineStringsEnabled) {
                        state.stringState = getStringStateForDelimiter(c);
                    }
                    return tokensToDraw;
                }
                i = end + 1;
                continue;
            }

            if ((c == '{' || c == '}') && !isEscaped(line, i)) {
                if (c == '{') {
                    int column = (firstNonSpace >= 0) ? firstNonSpace : i;
                    float x = getGuideXForColumn(line, column, globalLine);
                    state.stack.push(new BracketGuideToken(column, x));
                } else if (c == '}') {
                    if (!state.stack.isEmpty()) {
                        state.stack.pop();
                    }
                }
            }

            i++;
        }

        return tokensToDraw;
    }

    private void advanceBracketGuideStateForLine(String line, int globalLine, BracketGuideState state) {
        if (line == null) line = "";
        int length = line.length();
        int firstNonSpace = getFirstNonSpaceIndex(line);

        int i = 0;
        boolean inLineComment = false;

        while (i < length) {
            if (inLineComment) break;

            if (state.inBlockComment) {
                int end = findBlockCommentEnd(line, i);
                if (end < 0) return;
                i = end + 2;
                state.inBlockComment = false;
                continue;
            }

            if (state.stringState != 0) {
                StringEndResult endResult = findStringEndForState(line, i, state.stringState);
                if (!endResult.found) return;
                i = endResult.endIndex;
                state.stringState = 0;
                continue;
            }

            if (isLineCommentStart(line, i)) {
                inLineComment = true;
                break;
            }

            if (isBlockCommentsEnabled
                    && i + 1 < length
                    && line.charAt(i) == '/'
                    && line.charAt(i + 1) == '*'
                    && !isTokenEscaped(line, i)) {
                int end = findBlockCommentEnd(line, i + 2);
                if (end < 0) {
                    state.inBlockComment = true;
                    return;
                }
                i = end + 2;
                continue;
            }

            if (isTripleQuoteStart(line, i) && !isEscaped(line, i)) {
                int end = findTripleQuoteEnd(line, i + 3);
                if (end < 0) {
                    if (isTripleQuoteStringsEnabled) {
                        state.stringState = STRING_STATE_TRIPLE;
                    }
                    return;
                }
                i = end + 3;
                continue;
            }

            char c = line.charAt(i);
            if (isStringDelimiter(c) && !isEscaped(line, i)) {
                int end = findStringEnd(line, i + 1, c);
                if (end < 0) {
                    if (isMultiLineStringsEnabled) {
                        state.stringState = getStringStateForDelimiter(c);
                    }
                    return;
                }
                i = end + 1;
                continue;
            }

            if ((c == '{' || c == '}') && !isEscaped(line, i)) {
                if (c == '{') {
                    int column = (firstNonSpace >= 0) ? firstNonSpace : i;
                    float x = getGuideXForColumn(line, column, globalLine);
                    state.stack.push(new BracketGuideToken(column, x));
                } else if (c == '}') {
                    if (!state.stack.isEmpty()) {
                        state.stack.pop();
                    }
                }
            }

            i++;
        }
    }

    private void drawBracketGuidesForLine(Canvas canvas, String line, int globalLine, List<BracketGuideToken> guideTokens) {
        if (!isBracketGuidesEnabled || guideTokens == null || guideTokens.isEmpty()) return;
        if (line == null) line = "";

        java.util.HashSet<Float> seen = new java.util.HashSet<>();
        float top = getDrawLineTop(globalLine);
        float bottom = top + lineHeight;

        for (BracketGuideToken token : guideTokens) {
            float x = token.x;
            if (!seen.add(x)) continue;
            if (!isWhitespaceAtX(line, globalLine, x)) {
                continue;
            }
            canvas.drawLine(x, top, x, bottom, bracketGuidePaint);
        }
    }

    private static int getFirstNonSpaceIndex(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) return i;
        }
        return -1;
    }

    private static List<BracketGuideToken> getGuideTokensFromStack(java.util.ArrayDeque<BracketGuideToken> stack) {
        List<BracketGuideToken> tokens = new ArrayList<>();
        for (BracketGuideToken token : stack) {
            tokens.add(token);
        }
        return tokens;
    }

    private float getGuideXForColumn(String line, int column, int globalLine) {
        if (line == null) line = "";
        if (column <= line.length()) {
            return measureText(line, column, globalLine);
        }
        float base = measureText(line, line.length(), globalLine);
        float spaceWidth = getVisualSpaceWidth(paint);
        return base + spaceWidth * (column - line.length());
    }

    private boolean isWhitespaceAtX(String line, int globalLine, float x) {
        if (line == null || line.isEmpty()) return true;
        if (x <= 0f) return Character.isWhitespace(line.charAt(0));
        float fullWidth = measureText(line, line.length(), globalLine);
        if (x >= fullWidth) return true;
        for (int i = 0; i < line.length(); i++) {
            float right = measureText(line, i + 1, globalLine);
            if (x < right) {
                return Character.isWhitespace(line.charAt(i));
            }
        }
        return true;
    }

    private void drawColorCodeBackgrounds(Canvas canvas, String line, int globalLine) {
        if (!isColorHighlightingEnabled || line.isEmpty()) {
            return;
        }

        if (line.indexOf('#') < 0 && line.indexOf('0') < 0) return;

        int[] triples = colorCodeBgCache.get(globalLine);
        if (triples == null) {
            ArrayList<Integer> tmp = null;
            Matcher matcher = COLOR_HEX_PATTERN.matcher(line);
            while (matcher.find()) {
                String colorString = matcher.group(0);
                if (colorString == null || colorString.isEmpty()) continue;

                int color;
                try {
                    if (colorString.startsWith("0x") || colorString.startsWith("0X")) {
                        String hex = colorString.substring(2);
                        if (hex.length() == 6) hex = "FF" + hex;
                        color = (int) Long.parseLong(hex, 16);
                    } else {
                        color = android.graphics.Color.parseColor(colorString);
                    }
                } catch (Exception e) {
                    continue;
                }

                int backgroundColor = (color & 0x00FFFFFF) | (0xC0 << 24);
                if (tmp == null) tmp = new ArrayList<>();
                tmp.add(matcher.start());
                tmp.add(matcher.end());
                tmp.add(backgroundColor);
            }
            if (tmp == null || tmp.isEmpty()) {
                triples = new int[0];
            } else {
                triples = new int[tmp.size()];
                for (int i = 0; i < tmp.size(); i++) triples[i] = tmp.get(i);
            }
            colorCodeBgCache.put(globalLine, triples);
        }

        if (triples.length == 0) return;

        float top = getDrawLineTop(globalLine);
        float bottom = top + lineHeight;
        colorOverlayPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i + 2 < triples.length; i += 3) {
            int start = triples[i];
            int end = triples[i + 1];
            int backgroundColor = triples[i + 2];

            float left = measureText(line, start, globalLine);
            float right = measureText(line, end, globalLine);
            colorOverlayPaint.setColor(backgroundColor);
            canvas.drawRect(left, top, right, bottom, colorOverlayPaint);
        }
    }

    private float measureText(String line, int length, int globalLine) {
        if (highlightRules.isEmpty() || line.isEmpty() || length == 0) {
            return measureTextWithVisualSpaces(line, 0, length, paint);
        }

        List<HighlightSpan> spans = highlightCache.get(globalLine);
        if (spans == null) {
            spans = calculateSpansForLine(line, globalLine);
            highlightCache.put(globalLine, spans);
        }

        if (spans.isEmpty()) {
            return measureTextWithVisualSpaces(line, 0, length, paint);
        }

        float totalWidth = 0;
        int lastEnd = 0;

        for (HighlightSpan span : spans) {
            if (lastEnd >= length) break;
            if (span.start >= length) break;
            if (span.start < lastEnd) continue;

            // Measure part before the span
            if (span.start > lastEnd) {
                int measureEnd = Math.min(span.start, length);
                totalWidth += measureTextWithVisualSpaces(line, lastEnd, measureEnd, paint);
            }

            lastEnd = span.start;

            // Measure the span itself
            int measureEnd = Math.min(span.end, length);
            totalWidth += measureTextWithVisualSpaces(line, lastEnd, measureEnd, span.paint);

            lastEnd = span.end;
        }

        // Measure remaining part
        if (lastEnd < length) {
            totalWidth += measureTextWithVisualSpaces(line, lastEnd, length, paint);
        }

        return totalWidth;
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
        teardropPath.reset();
        teardropPath.addOval(cx - handleRadius, cy, cx + handleRadius, cy + handleRadius * 2, Path.Direction.CW);
        canvas.drawPath(teardropPath, paint);
    }

        private void drawPopup(Canvas canvas) {
            Paint bgPaint = popupBgPaint;
            Paint txt = popupTextPaint;
    
            // reset rects (so .contains() is safe when buttons are hidden)
            btnCopyRect.setEmpty();
            btnCutRect.setEmpty();
            btnPasteRect.setEmpty();
            btnDeleteRect.setEmpty();
            btnSelectAllRect.setEmpty();
    
            // Buttons order
            final List<String> labels = new ArrayList<>();
            if (isMinimalPopup) {
                labels.add("Paste");
                labels.add("Select All");
            } else {
                final boolean hideCopyCut = shouldHideCopyCutForSelection();
                if (!hideCopyCut) {
                    labels.add("Copy");
                    labels.add("Cut");
                }
                labels.add("Paste");
                labels.add("Delete");
                labels.add("Select All");
            }
    
            if (labels.isEmpty()) {
                hidePopup();
                return;
            }
    
            final int btnCount = labels.size();
            float totalWidth = (btnWidth * btnCount) + (btnSpacing * (btnCount - 1)) + (popupPadding * 2);
            float totalHeight = btnHeight + (popupPadding * 2);
    
            // --- POPUP POSITIONING LOGIC ---
    
            float anchorX, anchorY_top, anchorY_bottom;
    
            if (isMinimalPopup || !hasSelection) {
                // Anchor to cursor
                String cursorLineText = getLineTextForRender(cursorLine);
                anchorX = getTextStartX() + measureText(cursorLineText, Math.max(0, Math.min(cursorChar, cursorLineText.length())), cursorLine) - scrollX;
                anchorY_top = cursorLine * lineHeight - scrollY;
                anchorY_bottom = (cursorLine + 1) * lineHeight - scrollY;
            } else {
                // Anchor to selection (existing logic)
                int nStartLine, nEndLine, nEndChar;
                String endLineText;
                if (comparePos(selStartLine, selStartChar, selEndLine, selEndChar) <= 0) {
                    nStartLine = selStartLine;
                    nEndLine = selEndLine;
                    nEndChar = selEndChar;
                    endLineText = getLineTextForRender(nEndLine);
                } else {
                    nStartLine = selEndLine;
                    nEndLine = selStartLine;
                    nEndChar = selStartChar;
                    endLineText = getLineTextForRender(nEndLine);
                }
    
                anchorY_top = nStartLine * lineHeight - scrollY;
                anchorY_bottom = (nEndLine + 1) * lineHeight - scrollY;
                anchorX = getTextStartX() + measureText(endLineText, Math.max(0, Math.min(nEndChar, endLineText.length())), nEndLine) - scrollX;
            }
    
            float proposedLeft = anchorX - totalWidth / 2f;
            if (proposedLeft < 0) proposedLeft = 0;
            if (proposedLeft + totalWidth > getWidth()) proposedLeft = getWidth() - totalWidth;
    
            final float popupVerticalPadding = lineHeight * 0.75f;
    
            float topAbove = anchorY_top - totalHeight - popupVerticalPadding;
            float topBelow = anchorY_bottom + popupVerticalPadding;
    
            float finalTop;
            float visibleBottomBound = getHeight() - keyboardHeight;
    
            if (topAbove >= 0) {
                finalTop = topAbove;
            } else if (topBelow + totalHeight <= visibleBottomBound) {
                finalTop = topBelow;
            } else {
                finalTop = Math.max(0, visibleBottomBound - totalHeight - popupPadding);
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

    private void showMinimalPopupAtCursor() {
        if (hasSelection) return;
        isMinimalPopup = true;
        showPopup = true;
        invalidate();
    }

    private void showPopupAtSelection() {
        if (!hasSelection) return;
        isMinimalPopup = false;
        showPopup = true;
        invalidate();
    }

    private void hidePopup() {
        showPopup = false;
        isMinimalPopup = false;
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
            maybeKickWindowLoad(getGlobalLineForY(scrollY));
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

        int lineCount = isCodeFoldingEnabled ? getVisibleLineCount() : (windowStartLine + linesWindow.size());
        if (isEof) {
            float paddingToUse = (keyboardHeight > 0) ? Math.min(BOTTOM_SCROLL_OFFSET, keyboardHeight * 0.4f) : BOTTOM_SCROLL_OFFSET;
            maxScroll = Math.max(0f, lineCount * lineHeight - (effectiveHeight - paddingToUse));
        } else {
            float virtualExtraSpace = Math.max(prefetchLines * lineHeight, 2000f);
            maxScroll = Math.max(0f, lineCount * lineHeight + virtualExtraSpace - effectiveHeight);
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

        int firstVisibleIndex = (int) (scrollY / lineHeight);
        int lastVisibleIndex = firstVisibleIndex + (int) Math.ceil(getHeight() / lineHeight);
        int firstVisibleLine = mapVisibleIndexToGlobal(firstVisibleIndex);
        int lastVisibleLine = mapVisibleIndexToGlobal(lastVisibleIndex);
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
        loadWindowAround(startLine, onComplete, true);
    }

    private void loadWindowAround(int startLine, @Nullable Runnable onComplete, boolean recalculateWidthSync) {
        if (isWindowLoading) return;
        // Cancel any in-flight async width calculation for a previous window.
        maxWidthRecalcToken++;

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
                    if (recalculateWidthSync) {
                        recalculateMaxLineWidth();
                    } else {
                        synchronized (lineWidthCache) { lineWidthCache.clear(); }
                        currentMaxWindowLineWidth = 0f;
                        globalMaxLineWidth = 0f;
                        recalculateMaxLineWidthAsync();
                    }
                    invalidate();
                    if (onComplete != null) onComplete.run();
                });
            } catch (Exception e) {
                e.printStackTrace();
                post(() -> { isWindowLoading = false; if (onComplete != null) onComplete.run(); });
            }
        });
    }

    private void finishInitialFileOpenWarmup(final int token) {
        if (!isInitialFileOpenLoading) return;
        if (token != initialFileOpenToken) return;
        if (getHeight() <= 0 || lineHeight <= 0f) {
            postDelayed(() -> finishInitialFileOpenWarmup(token), 16);
            return;
        }

        int firstVisibleLine = Math.max(0, getGlobalLineForY(scrollY));
        int viewHeight = getHeight() - keyboardHeight;
        if (viewHeight <= 0) viewHeight = getHeight();
        int visibleLines = Math.max(1, (int) Math.ceil(viewHeight / lineHeight) + 2);
        int lastVisibleLine = firstVisibleLine + visibleLines;

        ensureHighlightCacheForVisibleRange(firstVisibleLine, lastVisibleLine, null);
        isInitialFileOpenLoading = false;
        if (initialFileOpenShowSpinner != null) {
            mainHandler.removeCallbacks(initialFileOpenShowSpinner);
            initialFileOpenShowSpinner = null;
        }
        setDisable(false);
        showLoadingCircle(false);
        invalidate();
    }

    private void recalculateMaxLineWidthAsync() {
        final int token = ++maxWidthRecalcToken;
        final int startLine;
        final ArrayList<String> snapshot;
        synchronized (linesWindow) {
            startLine = windowStartLine;
            snapshot = new ArrayList<>(linesWindow);
        }
        if (snapshot.isEmpty()) return;

        final int chunkSize = 120;
        post(new Runnable() {
            int index = 0;
            float mx = 0f;

            @Override
            public void run() {
                if (token != maxWidthRecalcToken) return;
                int end = Math.min(snapshot.size(), index + chunkSize);
                for (int i = index; i < end; i++) {
                    String line = snapshot.get(i);
                    if (line == null) line = "";
                    float w = measureTextWithVisualSpaces(line, 0, line.length(), paint);
                    synchronized (lineWidthCache) { lineWidthCache.put(startLine + i, w); }
                    if (w > mx) mx = w;
                }
                currentMaxWindowLineWidth = mx;
                globalMaxLineWidth = Math.max(globalMaxLineWidth, mx);
                index = end;
                if (index < snapshot.size()) {
                    post(this);
                } else {
                    clampScrollX();
                    invalidate();
                }
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
        clearHighlightCaches();
        if (isCodeFoldingEnabled) {
            foldRanges.clear();
            foldIntervalsDirty = true;
        }
    }

    public void clearContent() {
        invalidatePendingIO();
        sourceFile = null;
        isFileCleared = true;
        isSelectAllActive = false;
        isEntireFileSelected = false;
        isIndexReady = false;

        synchronized (linesWindow) {
            linesWindow.clear();
            linesWindow.add("");
        }
        synchronized (modifiedLines) { modifiedLines.clear(); }
        synchronized (lineWidthCache) { lineWidthCache.clear(); }
        clearHighlightCaches();
        currentMaxWindowLineWidth = 0f;
        globalMaxLineWidth = 0f;

        cursorLine = 0;
        cursorChar = 0;
        isEof = true;
        scrollY = 0;
        scrollX = 0;

        recalculateMaxLineWidth();
        requestLayout();
        invalidate();
    }

    public void loadFromFile(final File file) {
        invalidatePendingIO();
        isFileCleared = false;
        isSelectAllActive = false;
        isEntireFileSelected = false;

        final int token = ++initialFileOpenToken;
        isInitialFileOpenLoading = true;
        if (showLoadingOnFileOpen) {
            if (initialFileOpenShowSpinner != null) {
                mainHandler.removeCallbacks(initialFileOpenShowSpinner);
            }
            initialFileOpenShowSpinner = () -> {
                if (!showLoadingOnFileOpen) return;
                if (!isInitialFileOpenLoading) return;
                if (token != initialFileOpenToken) return;
                setDisable(true);
                showLoadingCircle(true);
            };
            mainHandler.postDelayed(initialFileOpenShowSpinner, 80);
        }

        sourceFile = file;
        windowStartLine = 0;
        synchronized (linesWindow) {
            linesWindow.clear();
        }
        synchronized (modifiedLines) { modifiedLines.clear(); }
        synchronized (lineWidthCache) { lineWidthCache.clear(); }
        clearHighlightCaches();
        currentMaxWindowLineWidth = 0f;
        globalMaxLineWidth = 0f;
        synchronized (lineOffsetsLock) { lineOffsets = new long[0]; }
        isIndexReady = false;

        cursorLine = 0;
        cursorChar = 0;
        isEof = false;
        scrollY = 0;
        scrollX = 0;

        loadWindowAround(0, () -> finishInitialFileOpenWarmup(token), false);
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
        // The keyboard should not be hidden automatically when the view is disabled
        // for background operations, as this provides a poor user experience for
        // quick operations like 'select all' -> 'delete'. The modal loading
        // indicator is sufficient to block interaction.
        // if (disable) {
        //     InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        //     if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        // }
    }

    private void restartInput() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(this);
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

    public void setShowLoadingOnFileOpen(boolean enabled) {
        showLoadingOnFileOpen = enabled;
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

    private static final int LARGE_PASTE_LINES = 1500;
    private static final int LARGE_PASTE_CHARS = 200_000;

    private static boolean isLargePasteText(String text) {
        if (text == null) return false;
        if (text.length() >= LARGE_PASTE_CHARS) return true;
        int newLines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++newLines >= LARGE_PASTE_LINES) return true;
        }
        return false;
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
                clearHighlightCaches();
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
                invalidateHighlightCacheForLine(cursorLine);
                cursorChar++;
                float newWidth = measureTextWithVisualSpaces(modified, 0, modified.length(), paint);
                synchronized (lineWidthCache) { lineWidthCache.put(cursorLine, newWidth); }
                currentMaxWindowLineWidth = Math.max(currentMaxWindowLineWidth, newWidth);
                globalMaxLineWidth = Math.max(globalMaxLineWidth, currentMaxWindowLineWidth);
            }
            invalidate();
            keepCursorVisibleHorizontally();
        }
        updateSuggestion();
    }

    public void insertNewlineAtCursor() {
        if (hasSelection) {
            replaceSelectionWithText("\n");
            return;
        }

        BracketPairType pairType = getCursorBracketPairType();
        if (isAutoBracketNewlineEnabled && pairType != BracketPairType.NONE) {
            String baseIndent = "";
            String innerIndent = "";
            if (isAutoBracketNewlineIndentEnabled) {
                baseIndent = getLineLeadingWhitespace(cursorLine);
                innerIndent = baseIndent + "  ";
            }

            String closeIndent = (pairType == BracketPairType.CURLY) ? baseIndent : innerIndent;
            String insertText = "\n" + innerIndent + "\n" + closeIndent;

            int targetLine = cursorLine + 1;
            int targetChar = innerIndent.length();
            insertTextAtCursor(insertText);

            cursorLine = targetLine;
            cursorChar = targetChar;
            resetCursorBlink();
            keepCursorVisibleHorizontally();
            invalidate();
            updateSuggestion();
            return;
        }

        if (isAutoBracketNewlineIndentEnabled) {
            String baseIndent = getLineLeadingWhitespace(cursorLine);
            insertTextAtCursor("\n" + baseIndent);
            return;
        }

        insertCharAtCursor('\n');
    }

    private BracketPairType getCursorBracketPairType() {
        String ln = getLineTextForRender(cursorLine);
        if (ln == null) return BracketPairType.NONE;
        if (cursorChar <= 0 || cursorChar >= ln.length()) return BracketPairType.NONE;

        char left = ln.charAt(cursorChar - 1);
        char right = ln.charAt(cursorChar);
        if (left == '{' && right == '}') return BracketPairType.CURLY;
        if (left == '(' && right == ')') return BracketPairType.ROUND;
        if (left == '[' && right == ']') return BracketPairType.SQUARE;
        return BracketPairType.NONE;
    }

    private String getLineLeadingWhitespace(int line) {
        String ln = getLineTextForRender(line);
        if (ln == null || ln.isEmpty()) return "";
        int i = 0;
        while (i < ln.length()) {
            char c = ln.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return (i == 0) ? "" : ln.substring(0, i);
    }

    private enum BracketPairType {
        NONE,
        CURLY,
        ROUND,
        SQUARE
    }

    public void deleteCharAtCursor() {
        invalidatePendingIO();
        editVersion.incrementAndGet();
        clearActiveSuggestion(); // Clear suggestion on delete

        if (hasComposing) { deleteComposing(); return; }
        if (isFileCleared) {
            if (cursorLine == 0 && cursorChar > 0) {
                cursorChar = Math.max(0, cursorChar - 1);
                invalidate();
            }
            updateSuggestion();
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
                if (isCharAnimationEnabled) {
                    String removed = base.substring(safeStart, Math.min(cursorChar, base.length()));
                    Paint p = getPaintForChar(cursorLine, safeStart, base);
                    startDeleteAnimation(cursorLine, safeStart, removed, p);
                }
                String modified = base.substring(0, safeStart) + base.substring(cursorChar);
                updateLocalLine(localIdx, modified);
                modifiedLines.put(cursorLine, modified);
                invalidateHighlightCacheForLine(cursorLine);
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
                clearHighlightCaches();

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
        updateSuggestion(); // Update suggestion after deletion
    }

    public void deleteForwardAtCursor() {
        invalidatePendingIO();
        editVersion.incrementAndGet();
        clearActiveSuggestion(); // Clear suggestion on delete forward

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
                if (isCharAnimationEnabled) {
                    String removed = base.substring(cursorChar, Math.min(cursorChar + 1, base.length()));
                    Paint p = getPaintForChar(cursorLine, cursorChar, base);
                    startDeleteAnimation(cursorLine, cursorChar, removed, p);
                }
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
        updateSuggestion(); // Update suggestion after delete forward
    }

    private void commitComposing(boolean keepInText) {
        if (!hasComposing) return;
        hasComposing = false;
        composingLength = 0;
        lastComposingTextForCharAnim = null;
        invalidate();
        updateSuggestion();
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
            if (isCharAnimationEnabled) {
                String oldComposing = base.substring(start, end);
                String newComposing = (textSeq == null) ? "" : textSeq.toString();
                if (newComposing.length() < oldComposing.length()) {
                    String removed = null;
                    int at = start;
                    if (oldComposing.startsWith(newComposing)) {
                        removed = oldComposing.substring(newComposing.length());
                        at = start + newComposing.length();
                    } else if (oldComposing.endsWith(newComposing)) {
                        removed = oldComposing.substring(0, oldComposing.length() - newComposing.length());
                        at = start;
                    }

                    if (removed != null && !removed.isEmpty()) {
                        Paint p = getPaintForChar(composingLine, at, base);
                        startDeleteAnimation(composingLine, at, removed, p);
                    }
                }
            }
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
        updateSuggestion();
    }

    private void deleteComposing() {
        if (!hasComposing) return;
        replaceComposingWith("");
        hasComposing = false;
        composingLength = 0;
        lastComposingTextForCharAnim = null;
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
        clearActiveSuggestion(); // Clear suggestion when copying/cutting

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
            return buildSelectedTextFromWindow(sL, sC, eL, eC, maxChars);
        }

        // If the selection is fully inside the current window, prefer the window snapshot to avoid
        // stale file reads while edits are pending.
        boolean fullyInWindow = (sL >= windowStartLine) && (eL < windowStartLine + linesWindow.size());
        if (fullyInWindow) {
            return buildSelectedTextFromWindow(sL, sC, eL, eC, maxChars);
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

    private String buildSelectedTextFromWindow(int sL, int sC, int eL, int eC, int maxChars) {
        StringBuilder sb = new StringBuilder();
        synchronized (linesWindow) {
            for (int L = sL; L <= eL; L++) {
                int local = L - windowStartLine;
                String ln = (local >= 0 && local < linesWindow.size()) ? linesWindow.get(local) : "";
                if (ln == null) ln = "";
                int startIdx = (L == sL) ? Math.min(sC, ln.length()) : 0;
                int endIdx = (L == eL) ? Math.min(eC, ln.length()) : ln.length();
                if (endIdx > startIdx) sb.append(ln, startIdx, endIdx);
                if (L < eL) sb.append('\n');

                if (sb.length() > maxChars) return sb.substring(0, maxChars);
            }
        }
        return sb.toString();
    }

    public void pasteFromClipboard() {
        invalidatePendingIO();
        editVersion.incrementAndGet();
        clearActiveSuggestion(); // Clear suggestion when pasting

        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence txt = cd.getItemAt(0).coerceToText(getContext());
        if (txt == null) return;
        insertTextAtCursor(txt.toString());
        updateSuggestion(); // Update suggestion after pasting
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
        clearActiveSuggestion(); // Clear suggestion when selecting all
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
        clearActiveSuggestion(); // Clear suggestion when deleting selection
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
        clearActiveSuggestion(); // Clear suggestion when replacing selection

        if (insertText == null) insertText = "";

        if (!hasSelection) {
            if (!insertText.isEmpty()) insertTextAtCursor(insertText);
            // No selection means no large edit UI was started for it.
            updateSuggestion();
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
            updateSuggestion();
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
            updateSuggestion();
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
            updateSuggestion();
            return;
        }

        final File inFile = sourceFile;
        // ابدأ إعادة كتابة الملف في الخلفية بدون تعطيل الواجهة وبدون دائرة تحميل.
        rewriteReplaceRangeAsync(opToken, inFile, sL, sC, eL, eC, insertText, target, false);
        updateSuggestion();
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
                                         String insertText, CursorTarget target,
                                         boolean finishLargeEditUi) {
        ioHandler.post(() -> {
            try {
                if (inFile == null || !inFile.exists()) {
                    post(() -> {
                        if (finishLargeEditUi) endLargeEditUi(true);
                    });
                    return;
                }

                RangeBytes range = computeByteRangeFastOrScan(inFile, sL, sC, eL, eC);
                if (range == null) {
                    post(() -> {
                        if (finishLargeEditUi) endLargeEditUi(true);
                    });
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
                        if (finishLargeEditUi) endLargeEditUi(false);
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
                            if (finishLargeEditUi) endLargeEditUi(false);
                            invalidate();
                        });
                    }
                });

            } catch (Exception ex) {
                ex.printStackTrace();
                post(() -> {
                    if (finishLargeEditUi) endLargeEditUi(true);
                });
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
        if (text == null || text.isEmpty()) return 0;
        if (x <= 0f) return 0;

        int len = text.length();
        if (getVisualSpaceScale() == 1) {
            int count = paint.breakText(text, true, x, null);
            if (count <= 0) return 0;
            if (count >= len) return len;

            // Choose nearest boundary between (count-1) and count based on midpoint of last glyph.
            float wPrev = (count > 1) ? paint.measureText(text, 0, count - 1) : 0f;
            float wCount = paint.measureText(text, 0, count);
            float mid = wPrev + (wCount - wPrev) * 0.5f;
            return (x < mid) ? (count - 1) : count;
        }

        if (measureWidthBuffer == null || measureWidthBuffer.length < len) {
            measureWidthBuffer = new float[len];
        }
        paint.getTextWidths(text, 0, len, measureWidthBuffer);
        float current = 0f;
        for (int i = 0; i < len; i++) {
            float adv = getCharAdvanceWidth(text.charAt(i), measureWidthBuffer[i], paint);
            float mid = current + adv * 0.5f;
            if (x < mid) return i;
            if (x < current + adv) return i + 1;
            current += adv;
        }
        return len;
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
        final int opToken = editVersion.incrementAndGet();

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

        // For very large pastes into a file-backed document, avoid expanding the in-memory window and doing
        // expensive per-line work on the UI thread. Instead, apply the insert via the file rewrite path.
        if (sourceFile != null && !isFileCleared && isLargePasteText(text)) {
            beginLargeEditUiIfNeeded(true, cursorLine, cursorLine, true);
            // Extend the watchdog for large paste operations; they can legitimately take longer than
            // the default safety timeout.
            mainHandler.removeCallbacks(largeEditUiWatchdog);
            mainHandler.postDelayed(largeEditUiWatchdog, 30_000);
            CursorTarget target = computeCursorAfterInsert(cursorLine, cursorChar, text);
            final File inFile = sourceFile;
            rewriteReplaceRangeAsync(opToken, inFile, cursorLine, cursorChar, cursorLine, cursorChar, text, target, true);
            updateSuggestion();
            return;
        }

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
        updateSuggestion();
    }

    private void ensureLineInWindow(int globalLine, boolean blockingIfAbsent) {
        clearActiveSuggestion(); // Clear suggestion when window/view changes
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
        String safe = (line == null) ? "" : line;
        float w = measureTextWithVisualSpaces(safe, 0, safe.length(), paint);
        synchronized (lineWidthCache) { lineWidthCache.put(globalIndex, w); }
    }

    private float getWidthForLine(int globalIndex, String line) {
        synchronized (lineWidthCache) {
            Float v = lineWidthCache.get(globalIndex);
            if (v != null) return v;
        }
        String safe = (line == null) ? "" : line;
        float w = measureTextWithVisualSpaces(safe, 0, safe.length(), paint);
        synchronized (lineWidthCache) { lineWidthCache.put(globalIndex, w); }
        return w;
    }

    private void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    public void setCharAnimation(boolean enabled, int durationMs) {
        isCharAnimationEnabled = enabled;
        if (durationMs > 0) charAnimationDurationMs = durationMs;
        if (!enabled) {
            if (charAnimAnimator != null) charAnimAnimator.cancel();
            charAnimAnimator = null;
            charAnimAlpha = 0f;
            charAnimLine = -1;
            charAnimStartChar = 0;
            charAnimEndChar = 0;
            lastComposingTextForCharAnim = null;
            if (delAnimAnimator != null) delAnimAnimator.cancel();
            delAnimAnimator = null;
            delAnimAlpha = 0f;
            delAnimLine = -1;
            delAnimAtChar = 0;
            delAnimText = null;
            invalidate();
        }
    }

    private void startCharAnimationFromText(CharSequence committedText) {
        if (!isCharAnimationEnabled) return;
        if (committedText == null) return;

        final int targetLine = cursorLine;
        final int targetEndChar = cursorChar;

        int extractedCodePoint = -1;
        int extractedCharCount = 0;
        int i = committedText.length();
        while (i > 0) {
            int codePoint = Character.codePointBefore(committedText, i);
            i -= Character.charCount(codePoint);

            if (codePoint == '\n' || codePoint == '\r') continue;
            if (Character.isWhitespace(codePoint)) continue;

            extractedCodePoint = codePoint;
            extractedCharCount = Character.charCount(codePoint);
            break;
        }
        if (extractedCodePoint == -1) return;

        final int finalCharCount = extractedCharCount;
        Runnable start = () -> {
            if (delAnimAnimator != null) delAnimAnimator.cancel();
            if (charAnimAnimator != null) charAnimAnimator.cancel();
            charAnimLine = targetLine;
            charAnimEndChar = Math.max(0, targetEndChar);
            charAnimStartChar = Math.max(0, charAnimEndChar - finalCharCount);
            charAnimAlpha = 0.2f;
            invalidateLineGlobal(charAnimLine); // draw immediately

            charAnimAnimator = ValueAnimator.ofFloat(0.2f, 1f);
            charAnimAnimator.setDuration(Math.max(1, charAnimationDurationMs));
            charAnimAnimator.addUpdateListener(a -> {
                Object v = a.getAnimatedValue();
                charAnimAlpha = (v instanceof Float) ? (Float) v : 0f;
                invalidateLineGlobal(charAnimLine);
            });
            charAnimAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    charAnimAlpha = 0f;
                    charAnimLine = -1;
                    invalidate();
                }

                @Override public void onAnimationCancel(Animator animation) {
                    charAnimAlpha = 0f;
                    charAnimLine = -1;
                    invalidate();
                }
            });
            charAnimAnimator.start();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else post(start);
    }

    private void startDeleteAnimation(int targetLine, int atChar, @Nullable String removedText, @Nullable Paint paintToUse) {
        if (!isCharAnimationEnabled) return;
        if (removedText == null || removedText.isEmpty()) return;

        final int lineForAnim = targetLine;
        final int atForAnim = Math.max(0, atChar);
        final String textForAnim = removedText;
        final Paint p = (paintToUse != null) ? paintToUse : paint;

        Runnable start = () -> {
            if (charAnimAnimator != null) charAnimAnimator.cancel();
            if (delAnimAnimator != null) delAnimAnimator.cancel();
            delAnimLine = lineForAnim;
            delAnimAtChar = atForAnim;
            delAnimText = textForAnim;
            delAnimPaint = p;
            delAnimAlpha = 1f;
            invalidateLineGlobal(lineForAnim);

            delAnimAnimator = ValueAnimator.ofFloat(1f, 0f);
            delAnimAnimator.setDuration(Math.max(1, charAnimationDurationMs));
            delAnimAnimator.addUpdateListener(a -> {
                Object v = a.getAnimatedValue();
                delAnimAlpha = (v instanceof Float) ? (Float) v : 0f;
                invalidateLineGlobal(lineForAnim);
            });
            delAnimAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    delAnimAlpha = 0f;
                    delAnimLine = -1;
                    delAnimAtChar = 0;
                    delAnimText = null;
                    delAnimPaint = null;
                    invalidate();
                }

                @Override public void onAnimationCancel(Animator animation) {
                    delAnimAlpha = 0f;
                    delAnimLine = -1;
                    delAnimAtChar = 0;
                    delAnimText = null;
                    delAnimPaint = null;
                    invalidate();
                }
            });
            delAnimAnimator.start();
        };

        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else post(start);
    }

    private void handleAutoPairing(String text) {
        if (!isAutoPairingEnabled || text == null || text.length() == 0 || text.length() >= 100) return;

        char c = text.charAt(text.length() - 1);
        String closing = null;
        if (c == '(') closing = ")";
        else if (c == '{') closing = "}";
        else if (c == '[') closing = "]";
        else if (c == '"') closing = "\"";
        else if (c == '\'') closing = "'";
        else if (c == '`') closing = "`";
        else if (c == '*') {
             if (cursorChar >= 2) {
                 String ln = getLineTextForRender(cursorLine);
                 if (ln != null && ln.length() >= cursorChar && ln.charAt(cursorChar - 2) == '/') {
                     closing = "*/";
                 }
             }
        }

        if (closing != null) {
            insertTextAtCursor(closing);
            for (int i = 0; i < closing.length(); i++) {
                moveCursorLeft();
            }
        }
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
                
                String str = text.toString();

                if (hasSelection) {
                    replaceSelectionWithText(str);
                    commitComposing(true);
                    startCharAnimationFromText(text);
                    handleAutoPairing(str);
                    updateSuggestion();
                    return true;
                }

                if (hasComposing) {
                    replaceComposingWith(text);
                    commitComposing(true);
                    startCharAnimationFromText(text);
                    handleAutoPairing(str);
                    updateSuggestion();
                    return true;
                }

                insertTextAtCursor(str);
                commitComposing(true);
                startCharAnimationFromText(text);
                handleAutoPairing(str);

                updateSuggestion();
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (isDisabled) return true;
                if (text == null) return true;

                if (hasSelection) {
                    replaceSelectionWithText(text.toString());
                    startCharAnimationFromText(text);
                    updateSuggestion();
                    return true;
                }

                ensureLineInWindow(cursorLine, true);
                if (!hasComposing) {
                    composingLine = cursorLine; composingOffset = cursorChar; composingLength = 0; hasComposing = true;
                }
                String newText = text.toString();
                String oldText = (lastComposingTextForCharAnim == null) ? "" : lastComposingTextForCharAnim;
                boolean shouldAnim = newText.length() >= oldText.length() && !newText.equals(oldText);
                replaceComposingWith(newText);
                lastComposingTextForCharAnim = newText;
                if (shouldAnim) startCharAnimationFromText(newText);
                updateSuggestion();
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (isDisabled) return true;

                if (hasSelection) {
                    replaceSelectionWithText("");
                    updateSuggestion();
                    return true;
                }
                for (int i = 0; i < beforeLength; i++) deleteCharAtCursor();
                for (int i = 0; i < afterLength; i++) deleteForwardAtCursor();
                updateSuggestion();
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
                suggestionAcceptedThisTouch = false; // Reset flag for new touch sequence

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
                float gy = ey + scrollY - getHitTestBaseY();
                if (hasSelection && leftHandleRect.contains(gx, gy)) { draggingHandle = 1; return true; }
                else if (hasSelection && rightHandleRect.contains(gx, gy)) { draggingHandle = 2; return true; }
                else if (isFocused() && !hasSelection && cursorHandleRect.contains(gx, gy)) { draggingHandle = 3; return true; }

                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ex - downX) > touchSlop || Math.abs(ey - downY) > touchSlop) movedSinceDown = true;

                if (isLineNumberSelecting) {
                    float y = ey + scrollY;
                    int line = getGlobalLineForY(y);
                    updateLineNumberSelection(line);
                    return true;
                }

                    if (draggingHandle != 0) {
                        updateHandlePosition(ex, ey);
                        if (draggingHandle == 1 || draggingHandle == 2) showPopupAtSelection();

                        float scrollMargin = lineHeight * 2, scrollSpeed = 25f;
                        autoScrollY = 0; autoScrollX = 0;
                        if (ey < scrollMargin) autoScrollY = -scrollSpeed;
                        else if (ey > (getHeight() - keyboardHeight) - scrollMargin) autoScrollY = scrollSpeed;
                        if (ex < scrollMargin) autoScrollX = -scrollSpeed;
                        else if (ex > getWidth() - scrollMargin) autoScrollX = scrollSpeed;

                        // Prevent horizontal auto-scroll when the handle is already at the line boundary.
                        if (autoScrollX > 0 && lastDragAtLineEnd) autoScrollX = 0;
                        if (autoScrollX < 0 && lastDragAtLineStart) autoScrollX = 0;

                        if (autoScrollX != 0 || autoScrollY != 0) mainHandler.post(autoScrollRunnable);
                        else mainHandler.removeCallbacks(autoScrollRunnable);

                        invalidate();
                        return true;
                    }

                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_UP:
                mainHandler.removeCallbacks(autoScrollRunnable);

                if (isLineNumberSelecting) {
                    isLineNumberSelecting = false;
                    lineNumberSelectAnchorLine = -1;
                    selecting = false;
                    pointerDown = false;
                    if (hasSelection) showPopupAtSelection();
                    return true;
                }
                
                // --- Check for tap on suggestion FIRST and consume if it's a clean tap ---
                float y = event.getY() + scrollY;
                float x = event.getX() + scrollX - getTextStartX();
                int line = getGlobalLineForY(y);
               
                // Get line text safely
                String ln = getLineFromWindowLocal(line - windowStartLine);
                if (ln == null) ln = getLineTextForRender(line);

                int charIndex = getCharIndexForX(ln, x);

                // Check if the long press was on an "empty" area
                boolean isEmptyArea = false;
                if (ln.isEmpty()) {
                    isEmptyArea = true;
                } else if (charIndex >= ln.length()) {
                    isEmptyArea = true; // Tapped on empty space after the text on a line
                
                }

                if (!movedSinceDown && isAutoCompletionEnabled && activeSuggestion != null && !activeSuggestionRect.isEmpty()) {
                	
                	if (activeSuggestionRect.contains(ex, ey)){
                    Log.d("PopEditText", "onTouchEvent.ACTION_UP: Suggestion tap detected. Calling acceptAutoCompletion.");
                    acceptAutoCompletion(); // Call synchronously
                    pointerDown = false; // Reset pointerDown state
                    Log.d("PopEditText", "onTouchEvent.ACTION_UP: Suggestion accepted, returning true.");
                    return true; // Consume the event, preventing further processing
                } else if (isEmptyArea && line == cursorLine) {
                    Log.d("PopEditText", "onTouchEvent.ACTION_UP: Suggestion tap detected. Calling acceptAutoCompletion.");
                    acceptAutoCompletion(); // Call synchronously
                    pointerDown = false; // Reset pointerDown state
                    Log.d("PopEditText", "onTouchEvent.ACTION_UP: Suggestion accepted, returning true.");
                    return true; // Consume the event, preventing further processing
                  } 
                }
                // --- END Check ---

                pointerDown = false;
                //clearActiveSuggestion(); 

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

                if (movedSinceDown && scroller.isFinished()) { // Just finished a scroll/drag
                    if(hasSelection) showPopupAtSelection();
                    restartInput(); // Sync IME state
                    Log.d("PopEditText", "onTouchEvent.ACTION_UP: Scroll/Zoom ended, restarted input.");
                }

                Log.d("PopEditText", "onTouchEvent.ACTION_UP: Passing to GestureDetector.ACTION_UP.");
                gestureDetector.onTouchEvent(event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(autoScrollRunnable);
                pointerDown = false; draggingHandle = 0; selecting = false;
                isLineNumberSelecting = false;
                lineNumberSelectAnchorLine = -1;
                clearActiveSuggestion(); // Clear suggestion on touch cancel
                Log.d("PopEditText", "onTouchEvent.ACTION_CANCEL: Passing to GestureDetector.");
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

        int line = getGlobalLineForY(y);

        if (isEof) {
            int lastValidLine = windowStartLine + linesWindow.size() - 1;
            if (line > lastValidLine) line = lastValidLine;
        }

        ensureLineInWindow(line, true);
        String ln = getLineTextForRender(line);
        int charIndex = getCharIndexForX(ln, x);
        int clamped = Math.max(0, Math.min(charIndex, ln.length()));
        lastDragAtLineStart = clamped == 0;
        lastDragAtLineEnd = clamped == ln.length();

        if (draggingHandle == 1) {
            selStartLine = line; selStartChar = clamped;
        } else if (draggingHandle == 2) {
            selEndLine = line; selEndChar = clamped;
        } else if (draggingHandle == 3) {
            cursorLine = line; cursorChar = clamped;
            keepCursorVisibleHorizontally();
        }
    }

    private void drawSelectionSegment(
            Canvas canvas,
            float left,
            float top,
            float right,
            float bottom,
            boolean roundTopLeft,
            boolean roundTopRight,
            boolean roundBottomRight,
            boolean roundBottomLeft,
            Paint paint
    ) {
        if (right <= left || bottom <= top) return;

        float radius = Math.min(12f, Math.max(2f, lineHeight * 0.22f));
        // Keep vertical edges flush between lines to avoid "seam" lines when selecting multiple lines.
        float insetX = 0.5f;
        selectionRectTmp.set(left + insetX, top, right - insetX, bottom);

        if (!roundTopLeft && !roundTopRight && !roundBottomRight && !roundBottomLeft) {
            canvas.drawRect(selectionRectTmp, paint);
            return;
        }

        float tl = roundTopLeft ? radius : 0f;
        float tr = roundTopRight ? radius : 0f;
        float br = roundBottomRight ? radius : 0f;
        float bl = roundBottomLeft ? radius : 0f;

        selectionRadiiTmp[0] = tl; selectionRadiiTmp[1] = tl;
        selectionRadiiTmp[2] = tr; selectionRadiiTmp[3] = tr;
        selectionRadiiTmp[4] = br; selectionRadiiTmp[5] = br;
        selectionRadiiTmp[6] = bl; selectionRadiiTmp[7] = bl;

        selectionPathTmp.reset();
        selectionPathTmp.addRoundRect(selectionRectTmp, selectionRadiiTmp, Path.Direction.CW);
        canvas.drawPath(selectionPathTmp, paint);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDisabled) return true;

        if (hasSelection && event.isPrintingKey()) {
            int uc = event.getUnicodeChar();
            if (uc != 0) {
                String s = String.valueOf((char) uc);
                replaceSelectionWithText(s);
                startCharAnimationFromText(s);
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
        clearActiveSuggestion(); // Clear suggestion when cursor moves
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
        updateSuggestion(); // Update suggestion after cursor move
    }

    private void moveCursorRight() {
        clearActiveSuggestion(); // Clear suggestion when cursor moves
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
        updateSuggestion(); // Update suggestion after cursor move
    }

    private void moveCursorUp() {
        clearActiveSuggestion(); // Clear suggestion when cursor moves
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
        updateSuggestion(); // Update suggestion after cursor move
    }

    private void moveCursorDown() {
        clearActiveSuggestion(); // Clear suggestion when cursor moves
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
        updateSuggestion(); // Update suggestion after cursor move
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        clearActiveSuggestion(); // Clear suggestion on focus change
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
        int idx = isCodeFoldingEnabled ? getVisibleIndexForGlobalLine(globalLine) : globalLine;
        float top = (idx * lineHeight) - scrollY;
        invalidate(0, (int) Math.floor(top), getWidth(), (int) Math.ceil(top + lineHeight));
    }

    public int getLinesCount() {
        if (isIndexReady && lineOffsets.length > 0) return lineOffsets.length;
        if (isEof) return windowStartLine + linesWindow.size();
        if (!linesWindow.isEmpty()) return windowStartLine + linesWindow.size();
        return -1;
    }

    private boolean isLineHiddenByFold(int line) {
        if (!isCodeFoldingEnabled || foldRanges.isEmpty()) return false;
        rebuildFoldIntervalsIfNeeded();
        for (int[] interval : foldIntervals) {
            if (line < interval[0]) return false;
            if (line <= interval[1]) return true;
        }
        return false;
    }

    private FoldRange getFoldRangeAtStart(int line) {
        if (!isCodeFoldingEnabled) return null;
        FoldRange range = foldRanges.get(line);
        return (range != null && range.collapsed) ? range : null;
    }

    private void rebuildFoldIntervalsIfNeeded() {
        if (!foldIntervalsDirty) return;
        foldIntervalsDirty = false;
        foldIntervals.clear();
        if (!isCodeFoldingEnabled || foldRanges.isEmpty()) return;

        for (FoldRange range : foldRanges.values()) {
            if (!range.collapsed) continue;
            int start = range.startLine + 1;
            int end = range.endLine;
            if (end < start) continue;
            foldIntervals.add(new int[]{start, end});
        }
        if (foldIntervals.isEmpty()) return;

        Collections.sort(foldIntervals, (a, b) -> Integer.compare(a[0], b[0]));
        int write = 0;
        int[] cur = foldIntervals.get(0);
        for (int i = 1; i < foldIntervals.size(); i++) {
            int[] nxt = foldIntervals.get(i);
            if (nxt[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], nxt[1]);
            } else {
                foldIntervals.set(write++, cur);
                cur = nxt;
            }
        }
        foldIntervals.set(write++, cur);
        while (foldIntervals.size() > write) foldIntervals.remove(foldIntervals.size() - 1);
    }

    private int getHiddenLineCount() {
        if (!isCodeFoldingEnabled || foldRanges.isEmpty()) return 0;
        rebuildFoldIntervalsIfNeeded();
        int total = getLinesCount();
        if (total <= 0) total = windowStartLine + linesWindow.size();
        int hidden = 0;
        for (int[] interval : foldIntervals) {
            int s = interval[0];
            int e = Math.min(interval[1], total - 1);
            if (e >= s) hidden += (e - s + 1);
        }
        return hidden;
    }

    private int getVisibleLineCount() {
        int total = getLinesCount();
        if (total <= 0) total = windowStartLine + linesWindow.size();
        int visible = Math.max(1, total - getHiddenLineCount());
        return visible;
    }

    private int mapVisibleIndexToGlobal(int visibleIndex) {
        if (!isCodeFoldingEnabled) return visibleIndex;
        int total = getLinesCount();
        if (total <= 0) total = windowStartLine + linesWindow.size();
        int visibleTotal = getVisibleLineCount();
        int clamped = Math.max(0, Math.min(visibleIndex, Math.max(0, visibleTotal - 1)));
        int global = clamped;
        rebuildFoldIntervalsIfNeeded();
        for (int[] interval : foldIntervals) {
            if (global < interval[0]) break;
            global += (interval[1] - interval[0] + 1);
        }
        return Math.max(0, Math.min(global, total - 1));
    }

    private int getVisibleIndexForGlobalLine(int globalLine) {
        if (!isCodeFoldingEnabled) return globalLine;
        rebuildFoldIntervalsIfNeeded();
        int visible = globalLine;
        for (int[] interval : foldIntervals) {
            if (globalLine < interval[0]) break;
            if (globalLine <= interval[1]) return Math.max(0, interval[0] - 1);
            visible -= (interval[1] - interval[0] + 1);
        }
        return Math.max(0, visible);
    }

    private int getGlobalLineForY(float y) {
        int idx = Math.max(0, (int) (y / lineHeight));
        return mapVisibleIndexToGlobal(idx);
    }

    private boolean toggleFoldAtLine(int line) {
        if (!isCodeFoldingEnabled) return false;
        FoldRange existing = foldRanges.get(line);
        if (existing != null) {
            existing.collapsed = !existing.collapsed;
            foldIntervalsDirty = true;
            invalidate();
            return true;
        }

        FoldRange created = findFoldRangeForLine(line);
        if (created == null) return false;
        created.collapsed = true;
        foldRanges.put(created.startLine, created);
        foldIntervalsDirty = true;
        invalidate();
        return true;
    }

    private FoldRange findFoldRangeForLine(int line) {
        if (!isCodeFoldingEnabled) return null;
        if (line < 0) return null;

        RandomAccessFile raf = null;
        try {
            if (sourceFile != null && isIndexReady) {
                raf = new RandomAccessFile(sourceFile, "r");
            }

            String ln = getLineTextForFoldScan(line, raf);
            if (ln == null) return null;

            HighlightLineState startState = getLineStateAtStart(line);
            boolean inBlockComment = startState.inBlockComment && isBlockCommentsEnabled;
            int stringState = startState.stringState;
            if (!isBlockCommentsEnabled) inBlockComment = false;
            if (!isMultiLineStringsEnabled && stringState != STRING_STATE_TRIPLE) stringState = 0;
            if (!isBacktickStringsEnabled && stringState == STRING_STATE_BACKTICK) stringState = 0;
            if (!isTripleQuoteStringsEnabled && stringState == STRING_STATE_TRIPLE) stringState = 0;

            if (inBlockComment || stringState != 0) return null;

            int scanIndex = 0;
            while (true) {
                FoldToken token = findFoldTokenInLine(ln, scanIndex);
                if (token == null) return null;

                if (token.isBlockComment) {
                    int endLine = findBlockCommentEndLine(line, token.index, raf);
                    if (endLine > line) {
                        return new FoldRange(line, endLine, token.index, '/', '/', true);
                    }
                    scanIndex = token.index + 2;
                    continue;
                }

                FoldMatch match = findMatchingBracketFrom(line, token.index, token.openChar, raf);
                if (match != null && match.endLine > line) {
                    return new FoldRange(line, match.endLine, token.index, token.openChar, match.closeChar, false);
                }

                scanIndex = token.index + 1;
                if (scanIndex >= ln.length()) return null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String getLineTextForFoldScan(int line, @Nullable RandomAccessFile raf) {
        if (line < 0) return null;
        String mod = modifiedLines.get(line);
        if (mod != null) return mod;
        if (line >= windowStartLine && line < windowStartLine + linesWindow.size()) {
            String text = getLineFromWindowLocal(line - windowStartLine);
            return (text != null) ? text : "";
        }
        if (raf != null && isIndexReady) {
            long offset;
            synchronized (lineOffsetsLock) {
                if (line < 0 || line >= lineOffsets.length) return null;
                offset = lineOffsets[line];
            }
            try {
                return readLineUtf8AtByte(raf, offset);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class FoldToken {
        final int index;
        final boolean isBlockComment;
        final char openChar;
        FoldToken(int index, boolean isBlockComment, char openChar) {
            this.index = index;
            this.isBlockComment = isBlockComment;
            this.openChar = openChar;
        }
    }

    private static final class FoldMatch {
        final int endLine;
        final char closeChar;
        FoldMatch(int endLine, char closeChar) {
            this.endLine = endLine;
            this.closeChar = closeChar;
        }
    }

    private FoldToken findFoldTokenInLine(String line, int startIndex) {
        if (line == null || line.isEmpty()) return null;
        int len = line.length();
        int i = Math.max(0, startIndex);
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int stringState = 0;

        while (i < len) {
            if (inLineComment) break;

            if (inBlockComment) {
                int end = findBlockCommentEnd(line, i);
                if (end < 0) return null;
                i = end + 2;
                inBlockComment = false;
                continue;
            }

            if (stringState != 0) {
                StringEndResult endResult = findStringEndForState(line, i, stringState);
                if (!endResult.found) return null;
                i = endResult.endIndex;
                stringState = 0;
                continue;
            }

            if (isLineCommentStart(line, i)) {
                inLineComment = true;
                break;
            }

            if (isBlockCommentsEnabled
                    && i + 1 < len
                    && line.charAt(i) == '/'
                    && line.charAt(i + 1) == '*'
                    && !isTokenEscaped(line, i)) {
                return new FoldToken(i, true, '/');
            }

            if (isTripleQuoteStart(line, i) && !isEscaped(line, i)) {
                int end = findTripleQuoteEnd(line, i + 3);
                if (end < 0) return null;
                i = end + 3;
                continue;
            }

            char c = line.charAt(i);
            if (isStringDelimiter(c) && !isEscaped(line, i)) {
                int end = findStringEnd(line, i + 1, c);
                if (end < 0) return null;
                i = end + 1;
                continue;
            }

            if (isOpeningBracket(c) && !isEscaped(line, i)) {
                if (c == '{') return new FoldToken(i, false, c);
            }
            i++;
        }
        for (int j = Math.max(0, startIndex); j < len; j++) {
            char c = line.charAt(j);
            if ((c == '(' || c == '[') && !isEscaped(line, j)) {
                return new FoldToken(j, false, c);
            }
        }
        return null;
    }

    private int findBlockCommentEndLine(int startLine, int startIndex, @Nullable RandomAccessFile raf) {
        int totalLines = getLinesCount();
        if (totalLines <= 0) totalLines = Math.max(startLine + 1, windowStartLine + linesWindow.size());

        for (int line = startLine; line < totalLines; line++) {
            String text = getLineTextForFoldScan(line, raf);
            if (text == null) break;
            int from = (line == startLine) ? Math.min(startIndex + 2, text.length()) : 0;
            int end = findBlockCommentEnd(text, from);
            if (end >= 0) return line;
        }
        return -1;
    }

    private FoldMatch findMatchingBracketFrom(int startLine, int startIndex, char openChar, @Nullable RandomAccessFile raf) {
        int totalLines = getLinesCount();
        if (totalLines <= 0) totalLines = Math.max(startLine + 1, windowStartLine + linesWindow.size());

        HighlightLineState startState = getLineStateAtStart(startLine);
        boolean inBlockComment = startState.inBlockComment && isBlockCommentsEnabled;
        int stringState = startState.stringState;
        if (!isBlockCommentsEnabled) inBlockComment = false;
        if (!isMultiLineStringsEnabled && stringState != STRING_STATE_TRIPLE) stringState = 0;
        if (!isBacktickStringsEnabled && stringState == STRING_STATE_BACKTICK) stringState = 0;
        if (!isTripleQuoteStringsEnabled && stringState == STRING_STATE_TRIPLE) stringState = 0;

        if (inBlockComment || stringState != 0) return null;

        int depth = 1;
        char closeChar = matchingBracket(openChar);

        for (int line = startLine; line < totalLines; line++) {
            String text = getLineTextForFoldScan(line, raf);
            if (text == null) break;
            int len = text.length();
            int i = (line == startLine) ? Math.min(startIndex + 1, len) : 0;
            boolean inLineComment = false;

            while (i < len) {
                if (inLineComment) break;

                if (inBlockComment) {
                    int end = findBlockCommentEnd(text, i);
                    if (end < 0) break;
                    i = end + 2;
                    inBlockComment = false;
                    continue;
                }

                if (stringState != 0) {
                    StringEndResult endResult = findStringEndForState(text, i, stringState);
                    if (!endResult.found) break;
                    i = endResult.endIndex;
                    stringState = 0;
                    continue;
                }

                if (isLineCommentStart(text, i)) {
                    inLineComment = true;
                    break;
                }

                if (isBlockCommentsEnabled
                        && i + 1 < len
                        && text.charAt(i) == '/'
                        && text.charAt(i + 1) == '*'
                        && !isTokenEscaped(text, i)) {
                    int end = findBlockCommentEnd(text, i + 2);
                    if (end < 0) {
                        inBlockComment = true;
                        break;
                    }
                    i = end + 2;
                    continue;
                }

                if (isTripleQuoteStart(text, i) && !isEscaped(text, i)) {
                    int end = findTripleQuoteEnd(text, i + 3);
                    if (end < 0) {
                        if (isTripleQuoteStringsEnabled) {
                            stringState = STRING_STATE_TRIPLE;
                        }
                        break;
                    }
                    i = end + 3;
                    continue;
                }

                char c = text.charAt(i);
                if (isStringDelimiter(c) && !isEscaped(text, i)) {
                    int end = findStringEnd(text, i + 1, c);
                    if (end < 0) {
                        if (isMultiLineStringsEnabled) {
                            stringState = getStringStateForDelimiter(c);
                        }
                        break;
                    }
                    i = end + 1;
                    continue;
                }

                if (!isEscaped(text, i)) {
                    if (c == openChar) depth++;
                    else if (c == closeChar) {
                        depth--;
                        if (depth == 0) return new FoldMatch(line, closeChar);
                    }
                }
                i++;
            }
        }
        return null;
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
        float cursorX = measureText(line, Math.min(cursorChar, line.length()), cursorLine);

        float visibleWidth = getWidth() - paddingLeft;
        float scrollMargin = 50f;
        if (cursorX < scrollX + scrollMargin) scrollX = Math.max(0, cursorX - scrollMargin);
        else if (cursorX > scrollX + visibleWidth - scrollMargin) scrollX = cursorX - visibleWidth + scrollMargin;

        clampScrollX();
        invalidate();
    }

    private void drawAutoSuggestion(Canvas canvas, String lineContent, int globalLine, float textBaselineY) {
        if (!isAutoCompletionEnabled || activeSuggestion == null || globalLine != activeSuggestionLine) {
            return;
        }

        int cursorPositionInLine = activeSuggestionCharStart + activeSuggestionWordFragment.length();
        if (cursorPositionInLine > lineContent.length()) {
            clearActiveSuggestion();
            return;
        }

        // Calculate X position where the suggestion starts
        float suggestionStartX_canvas = measureText(lineContent, cursorPositionInLine, globalLine);
        
        // Draw the suggestion text
        canvas.drawText(activeSuggestion, suggestionStartX_canvas, textBaselineY, suggestionPaint);

        // Calculate and store the tap area in VIEW coordinates
        float suggestionTextWidth = suggestionPaint.measureText(activeSuggestion);
        
        // The canvas is translated by (getTextStartX() - scrollX, -scrollY)
        // To get view coordinates:
        // viewX = canvasX + (scrollX - getTextStartX())
        // viewY = canvasY + scrollY
        
        float left_view = suggestionStartX_canvas + getTextStartX() - scrollX;
        float right_view = left_view + suggestionTextWidth;
        float top_view = globalLine * lineHeight - scrollY;
        float bottom_view = (globalLine + 1) * lineHeight - scrollY;

        activeSuggestionRect.set(left_view, top_view, right_view, bottom_view);
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
        if (charAnimAnimator != null) charAnimAnimator.cancel();
        if (delAnimAnimator != null) delAnimAnimator.cancel();
        ioThread.quitSafely();
    }
} 
