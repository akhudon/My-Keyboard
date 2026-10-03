package juloo.keyboard2;

import android.graphics.Color;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.Arrays;
import java.util.List;

public class Keyboard2View extends View
  implements View.OnTouchListener, Pointers.IPointerEventHandler
{
  private KeyboardData _keyboard;

  /** The key holding the shift key is used to set shift state from
      autocapitalisation. */
  private KeyboardData.Key _shift_key;

  /** Used to add fake pointers. */
  private KeyboardData.Key _compose_key;

  private Pointers _pointers;

  private Pointers.Modifiers _mods;

  private static int _currentWhat = 0;

  private Config _config;
  private KeySoundCompat _keySound;

  private float _keyWidth;
  private float _mainLabelSize;
  private float _subLabelSize;
  private float _marginRight;
  private float _marginLeft;
  private float _marginBottom;
  private int _insets_left = 0;
  private int _insets_right = 0;
  private int _insets_bottom = 0;

  private Theme _theme;
  private Theme.Computed _tc;

  // Key preview shown while a key is being pressed.
  private KeyboardData.Key _previewKey;
  private int _previewPointerId = -1;
  private KeyValue _previewValue;
  private float _previewDownX;
  private float _previewDownY;
  private float _previewX;
  private float _previewY;
  private final Paint _previewBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _previewTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF _previewRect = new RectF();
// Temporary cursor trackpad activated by horizontal swiping on space bar.
  private boolean _spaceTrackpadActive = false;
  private int _spaceBarPointerId = -1;
  private int _spaceTrackpadPointerId = -1;
  private float _spaceBarDownX;
  private float _spaceBarDownY;
  private float _spaceTrackpadLastX;
  private float _spaceTrackpadLastY;

  private float _spaceTrackpadAccumulator = 0f;
  private float _spaceTrackpadVerticalAccumulator = 0f;
  private final Paint _spaceTrackpadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _spaceTrackpadTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  
  private static RectF _tmpRect = new RectF();

  enum Vertical
  {
    TOP,
    CENTER,
    BOTTOM
  }

  public Keyboard2View(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _theme = new Theme(getContext(), attrs);
    _config = Config.globalConfig();
    _keySound = new KeySoundCompat(getContext());
    _pointers = new Pointers(this, _config);
    refresh_navigation_bar(context);
    setOnTouchListener(this);
    int layout_id = (attrs == null) ? 0 :
      attrs.getAttributeResourceValue(null, "layout", 0);
    if (layout_id == 0)
      reset();
    else
      setKeyboard(KeyboardData.load(getResources(), layout_id));
  }

  private Window getParentWindow(Context context)
  {
    if (context instanceof InputMethodService)
      return ((InputMethodService)context).getWindow().getWindow();
    if (context instanceof ContextWrapper)
      return getParentWindow(((ContextWrapper)context).getBaseContext());
    return null;
  }

  public void refresh_navigation_bar(Context context)
  {
    if (VERSION.SDK_INT < 21)
      return;
    // The intermediate Window is a [Dialog].
    Window w = getParentWindow(context);
    w.setNavigationBarColor(_theme.colorNavBar);
    if (VERSION.SDK_INT < 26)
      return;
    int uiFlags = getSystemUiVisibility();
    if (_theme.isLightNavBar)
      uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    else
      uiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    setSystemUiVisibility(uiFlags);
  }

  public void setKeyboard(KeyboardData kw)
  {
    _keyboard = kw;
    _shift_key = _keyboard.findKeyWithValue(KeyValue.SHIFT);
    _compose_key = _keyboard.findKeyWithValue(KeyValue.COMPOSE);
    KeyModifier.set_modmap(_keyboard.modmap);
    reset();
  }

  public void reset()
  {
    _mods = Pointers.Modifiers.EMPTY;
    _pointers.clear();
    clearKeyPreview();
    requestLayout();
    invalidate();
  }

  void set_fake_ptr_latched(KeyboardData.Key key, KeyValue kv, boolean latched,
      boolean lock)
  {
    if (_keyboard == null || key == null)
      return;
    _pointers.set_fake_pointer_state(key, kv, latched, lock);
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    set_fake_ptr_latched(_shift_key, KeyValue.SHIFT, latched, lock);
  }

  /** Called from [KeyEventHandler]. */
  public void set_compose_pending(boolean pending)
  {
    set_fake_ptr_latched(_compose_key, KeyValue.COMPOSE, pending, false);
  }

  /** Called from [Keybard2.onUpdateSelection].  */
  public void set_selection_state(boolean selection_state)
  {
    if (_config.editor_config.selection_mode_enabled)
      set_fake_ptr_latched(KeyboardData.Key.EMPTY,
          KeyValue.SELECTION_MODE, selection_state, true);
  }

  public KeyValue modifyKey(KeyValue k, Pointers.Modifiers mods)
  {
    return KeyModifier.modify(k, mods);
  }

  public void onPointerDown(KeyValue k, boolean isSwipe)
  {
    updateFlags();
    _config.handler.key_down(k, isSwipe);
    invalidate();
    vibrate();
    playKeySound();
  }

  public void onPointerUp(KeyValue k, Pointers.Modifiers mods)
  {
    // [key_up] must be called before [updateFlags]. The latter might disable
    // flags.
    _config.handler.key_up(k, mods);
    updateFlags();
    invalidate();
  }

  public void onPointerHold(KeyValue k, Pointers.Modifiers mods)
  {
    _config.handler.key_up(k, mods);
    updateFlags();
  }

  public void onPointerFlagsChanged(boolean shouldVibrate)
  {
    updateFlags();
    invalidate();
    if (shouldVibrate)
      vibrate();
  }

  private void updateFlags()
  {
    _mods = _pointers.getModifiers();
    _config.handler.mods_changed(_mods);
  }

@Override
public boolean onTouch(View v, MotionEvent event)
{
  int p;

  switch (event.getActionMasked())
  {
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_POINTER_UP:
      p = event.getActionIndex();
      int upId = event.getPointerId(p);

      if (upId == _spaceTrackpadPointerId)
      {
        _spaceTrackpadActive = false;
        _spaceTrackpadPointerId = -1;
        _spaceBarPointerId = -1;
		_spaceTrackpadAccumulator = 0f;
		_spaceTrackpadVerticalAccumulator = 0f;
        clearKeyPreview();
        invalidate();
        break;
      }

      // While trackpad mode is active, ignore other fingers.
      if (_spaceTrackpadActive)
        break;

      _pointers.onTouchUp(upId);
      if (upId == _previewPointerId)
        clearKeyPreview();
      if (upId == _spaceBarPointerId)
        _spaceBarPointerId = -1;
      break;

    case MotionEvent.ACTION_DOWN:
    case MotionEvent.ACTION_POINTER_DOWN:
      if (_spaceTrackpadActive)
        break;

      p = event.getActionIndex();
      float tx = event.getX(p);
      float ty = event.getY(p);
      KeyboardData.Key key = getKeyAtPosition(tx, ty);

      if (key != null)
      {
        int pointerId = event.getPointerId(p);

        _pointers.onTouchDown(tx, ty, pointerId, key);
        showKeyPreview(key, pointerId, tx, ty);
        _previewDownX = tx;
        _previewDownY = ty;

        if (key.role == KeyboardData.Key.Role.Space_bar
            && key.keys[0] != null
            && key.keys[0].getKind() == KeyValue.Kind.Editing
            && key.keys[0].getEditing() == KeyValue.Editing.SPACE_BAR)
        {
          _spaceBarPointerId = pointerId;
          _spaceBarDownX = tx;
          _spaceBarDownY = ty;
        }
      }
      break;

    case MotionEvent.ACTION_MOVE:
      for (p = 0; p < event.getPointerCount(); p++)
      {
        float mx = event.getX(p);
        float my = event.getY(p);
        int moveId = event.getPointerId(p);

        // Trackpad mode: only the original space-bar finger is processed.
        if (_spaceTrackpadActive)
        {
          if (moveId == _spaceTrackpadPointerId)
			moveSpaceTrackpad(mx, my);
          continue;
        }

        // Check whether the horizontal space-bar swipe should activate
        // cursor trackpad mode before normal gesture processing sees it.
        if (moveId == _spaceBarPointerId)
        {
          float dx = mx - _spaceBarDownX;
          float dy = my - _spaceBarDownY;

          if (Config.globalPrefs().getBoolean("space_bar_cursor_control", true)
              && Math.abs(dx) >= _config.swipe_dist_px
              && Math.abs(dx) > Math.abs(dy))
          {
			_spaceTrackpadActive = true;
			_spaceTrackpadPointerId = moveId;

			_spaceTrackpadLastX = mx;
			_spaceTrackpadLastY = my;

			_spaceTrackpadAccumulator = 0f;
			_spaceTrackpadVerticalAccumulator = 0f;
            // Prevent the eventual finger-up from inserting a space.
            _pointers.cancelPointerForTrackpad(moveId);

            clearKeyPreview();
            invalidate();
            continue;
          }
        }

        _pointers.onTouchMove(mx, my, moveId);

        if (moveId == _previewPointerId)
        {
          updateKeyPreviewForSwipe(_previewKey, moveId, mx, my);
        }
      }
      break;

    case MotionEvent.ACTION_CANCEL:
      _spaceTrackpadActive = false;
      _spaceTrackpadPointerId = -1;
      _spaceBarPointerId = -1;
	  _spaceTrackpadAccumulator = 0f;
	  _spaceTrackpadVerticalAccumulator = 0f;

      _pointers.onTouchCancel();
      clearKeyPreview();
      invalidate();
      break;

    default:
      return false;
  }

  return true;
}

/* Move the text cursor according to horizontal and vertical finger movement. */
private void moveSpaceTrackpad(float x, float y)
{
  float dx = x - _spaceTrackpadLastX;
  float dy = y - _spaceTrackpadLastY;

  _spaceTrackpadLastX = x;
  _spaceTrackpadLastY = y;

  _spaceTrackpadAccumulator += dx;
  _spaceTrackpadVerticalAccumulator += dy;

  // Distance required for one cursor position.
  float stepPx = Math.max(_keyWidth * 0.12f, 1f);

  // Horizontal movement.
  int horizontalSteps =
      (int)(_spaceTrackpadAccumulator / stepPx);

  if (horizontalSteps != 0)
  {
    _spaceTrackpadAccumulator -= horizontalSteps * stepPx;

    KeyValue.Slider slider =
        horizontalSteps > 0
            ? KeyValue.Slider.Cursor_right
            : KeyValue.Slider.Cursor_left;

    _config.handler.key_up(
        KeyValue.sliderKey(
            slider,
            Math.abs(horizontalSteps)),
        _pointers.getModifiers());
  }

  // Vertical movement.
  int verticalSteps =
      (int)(_spaceTrackpadVerticalAccumulator / stepPx);

  if (verticalSteps != 0)
  {
    _spaceTrackpadVerticalAccumulator -= verticalSteps * stepPx;

    KeyValue.Slider slider =
        verticalSteps > 0
            ? KeyValue.Slider.Cursor_down
            : KeyValue.Slider.Cursor_up;

    _config.handler.key_up(
        KeyValue.sliderKey(
            slider,
            Math.abs(verticalSteps)),
        _pointers.getModifiers());
  }
}
  private KeyboardData.Row getRowAtPosition(float ty)
  {
    float y = _config.marginTop;
    if (ty < y)
      return null;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += (row.shift + row.height) * _tc.row_height;
      if (ty < y)
        return row;
    }
    return null;
  }

  private KeyboardData.Key getKeyAtPosition(float tx, float ty)
  {
    KeyboardData.Row row = getRowAtPosition(ty);
    float x = _marginLeft;
    if (row == null || tx < x)
      return null;
    for (KeyboardData.Key key : row.keys)
    {
      float xLeft = x + key.shift * _keyWidth;
      float xRight = xLeft + key.width * _keyWidth;
      if (tx < xLeft)
        return null;
      if (tx < xRight)
        return key;
      x = xRight;
    }
    return null;
  }

  private void vibrate()
  {
    VibratorCompat.vibrate(this, _config);
  }

  private void playKeySound()
  {
    _keySound.play(this, _config);
  }

  private void showKeyPreview(KeyboardData.Key key, int pointerId, float x, float y)
  {
    if (key == null || key.keys[0] == null)
    {
      clearKeyPreview();
      return;
    }
    _previewKey = key;
    _previewValue = modifyKey(key.keys[0], _mods);
    _previewPointerId = pointerId;
    _previewDownX = x;
    _previewDownY = y;
    // Anchor the popup to the physical key, not the moving finger position.
    // This keeps the preview directly above the pressed key during swipes.
    setKeyPreviewAnchor(key, x, y);
    invalidate();
  }

  private void setKeyPreviewAnchor(KeyboardData.Key key, float fallbackX, float fallbackY)
  {
    _previewX = fallbackX;
    _previewY = fallbackY;
    if (_keyboard == null || key == null || _tc == null)
      return;

    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      for (KeyboardData.Key candidate : row.keys)
      {
        x += candidate.shift * _keyWidth;
        float keyW = _keyWidth * candidate.width - _tc.horizontal_margin;
        if (candidate == key)
        {
          _previewX = x + keyW / 2f;
          _previewY = y;
          return;
        }
        x += _keyWidth * candidate.width;
      }
      y += row.height * _tc.row_height;
    }
  }

  private void clearKeyPreview()
  {
    _previewKey = null;
    _previewValue = null;
    _previewPointerId = -1;
    invalidate();
  }

  /** Update the popup to the sub-key selected by the swipe direction. */
  private void updateKeyPreviewForSwipe(KeyboardData.Key key, int pointerId, float x, float y)
  {
    if (key == null || key != _previewKey || pointerId != _previewPointerId)
      return;

    float dx = x - _previewDownX;
    float dy = y - _previewDownY;
    float dist = Math.abs(dx) + Math.abs(dy);

    // Keep the popup anchored above the original pressed key while the
    // selected character changes with the swipe direction.

    // Until the swipe threshold is crossed, keep showing the primary key.
    if (dist < _config.swipe_dist_px)
    {
      _previewValue = modifyKey(key.keys[0], _mods);
      invalidate();
      return;
    }

    // Use exactly the same 16-direction calculation as Pointers.java.
    // The direction is measured from the original key-down position, not
    // from whichever physical key the finger happens to be over now.
    double angle = Math.atan2(dy, dx) + Math.PI;
    int direction = ((int)(angle * 8.0 / Math.PI) + 12) % 16;

    KeyValue selected = getNearestPreviewKeyAtDirection(key, direction);
    selected = modifyKey(selected, _mods);

    // If that direction has no sub-key, retain the primary character.
    _previewValue = selected != null ? selected : modifyKey(key.keys[0], _mods);
    invalidate();
  }

  private KeyValue getNearestPreviewKeyAtDirection(KeyboardData.Key key, int direction)
  {
    // Match the real keyboard's nearest-side-key selection, but without
    // changing the popup's original key.
    for (int i = 0; i > -4; i = (~i >> 31) - i)
    {
      int d = (direction + i + 16) % 16;
      KeyValue value = modifyKey(Pointers.getKeyAtDirection(key, d), _mods);
      if (value != null)
      {
        if (value.getKind() == KeyValue.Kind.Slider && Math.abs(i) >= 2)
          continue;
        return value;
      }
    }
    return null;
  }

  private void drawKeyPreview(Canvas canvas)
  {
    if (_previewKey == null || _previewKey.keys[0] == null)
      return;

    KeyValue mainKv = _previewValue != null
        ? _previewValue : modifyKey(_previewKey.keys[0], _mods);
    if (mainKv == null)
      return;

    String label = mainKv.getString();
    if (label == null || label.length() == 0)
      return;

    float density = getResources().getDisplayMetrics().density;
    float paddingH = 14f * density;
    float paddingV = 9f * density;
    float textSize = Math.max(_mainLabelSize * 1.65f, 18f * density);

    _previewTextPaint.setTypeface(
        mainKv.hasFlagsAny(KeyValue.FLAG_KEY_FONT)
            ? Theme.getKeyFont(getContext()) : Typeface.DEFAULT);
    _previewTextPaint.setTextSize(textSize);
    _previewTextPaint.setTextAlign(Paint.Align.CENTER);
    _previewTextPaint.setColor(_theme.pressedColor);

    float textWidth = _previewTextPaint.measureText(label);
    float popupW = Math.max(textWidth + paddingH * 2f, 42f * density);
    float popupH = paddingV * 2f + textSize;
    float radius = Math.min(12f * density, popupH / 2f);
    float gap = 6f * density;

    float cx = _previewX;
    // The preview always belongs above the pressed key. For keys near the
    // top edge, clamp it to the top of the view instead of flipping it below.
    float top = Math.max(2f * density, _previewY - gap - popupH);
    float bottom = top + popupH;

    float left = cx - popupW / 2f;
    float right = cx + popupW / 2f;
    float maxW = getWidth() - 2f * density;
    if (popupW > maxW)
    {
      left = density;
      right = getWidth() - density;
    }
    else
    {
      if (left < density)
      {
        right += density - left;
        left = density;
      }
      if (right > getWidth() - density)
      {
        left -= right - (getWidth() - density);
        right = getWidth() - density;
      }
    }

    _previewBgPaint.setStyle(Paint.Style.FILL);
    _previewBgPaint.setColor(_theme.colorKeyActivated);
    _previewRect.set(left, top, right, bottom);
    canvas.drawRoundRect(_previewRect, radius, radius, _previewBgPaint);

    float textY = top + paddingV - _previewTextPaint.ascent();
    canvas.drawText(label, cx, textY, _previewTextPaint);
  }

  @Override
  public void onMeasure(int wSpec, int hSpec)
  {
    DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
    int width = dm.widthPixels;
    _marginLeft = Math.max(_config.horizontal_margin, _insets_left);
    _marginRight = Math.max(_config.horizontal_margin, _insets_right);
    _marginBottom = _config.margin_bottom + _insets_bottom;
    _keyWidth = (width - _marginLeft - _marginRight) / _keyboard.keysWidth;
    _tc = new Theme.Computed(_theme, _config, _keyWidth, _keyboard);
    // Compute the size of labels based on the width or the height of keys. The
    // margin around keys is taken into account. Keys normal aspect ratio is
    // assumed to be 3/2 for a 10 columns layout. It's generally more, the
    // width computation is useful when the keyboard is unusually high.
    float labelBaseSize = Math.min(
        _tc.row_height - _tc.vertical_margin,
        (width / 10 - _tc.horizontal_margin) * 3/2
        ) * _config.characterSize;
    _mainLabelSize = labelBaseSize * _config.labelTextSize;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    setMeasuredDimension(width, height);
  }

  Rect _cached_exclusion_rect = new Rect();
  List<Rect> _cached_exclusion_rects = Arrays.asList(_cached_exclusion_rect);
  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    if (!changed)
      return;
    // Since SDK 30, this is done automatically:
    // https://android.googlesource.com/platform/frameworks/base/+/android11-release/core/java/android/inputmethodservice/InputMethodService.java#852
    if (VERSION.SDK_INT == 29)
    {
      // Disable the back-gesture on the keyboard area
      _cached_exclusion_rect.set(
          left + (int)_marginLeft,
          top + (int)_config.marginTop,
          right - (int)_marginRight,
          bottom - (int)_marginBottom);
      setSystemGestureExclusionRects(_cached_exclusion_rects);
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets wi)
  {
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is set in [Keyboard2#updateSoftInputWindowLayoutParams] for SDK_INT >= 35.
    if (VERSION.SDK_INT < 35)
      return wi;
    int insets_types =
      WindowInsets.Type.systemBars()
      | WindowInsets.Type.displayCutout();
    Insets insets = wi.getInsets(insets_types);
    _insets_left = insets.left;
    _insets_right = insets.right;
    _insets_bottom = insets.bottom;
    return WindowInsets.CONSUMED;
  }

  /** Horizontal and vertical position of the 9 indexes. */
  static final Paint.Align[] LABEL_POSITION_H = new Paint.Align[]{
    Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
    Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
    Paint.Align.CENTER, Paint.Align.CENTER
  };

  static final Vertical[] LABEL_POSITION_V = new Vertical[]{
    Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
    Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
    Vertical.BOTTOM
  };

/* Draw the temporary trackpad surface over the whole keyboard. */
private void drawSpaceTrackpad(Canvas canvas)
{
  if (!_spaceTrackpadActive)
    return;

  _spaceTrackpadPaint.setStyle(Paint.Style.FILL);
  _spaceTrackpadPaint.setColor(Color.BLACK);
  _spaceTrackpadPaint.setAlpha(180);

  canvas.drawRect(
      0,
      0,
      getWidth(),
      getHeight(),
      _spaceTrackpadPaint);

  _spaceTrackpadTextPaint.setStyle(Paint.Style.FILL);
  _spaceTrackpadTextPaint.setColor(Color.WHITE);
  _spaceTrackpadTextPaint.setTextAlign(Paint.Align.CENTER);
  _spaceTrackpadTextPaint.setTextSize(Math.max(_mainLabelSize, 20f));

  Paint.FontMetrics fm = _spaceTrackpadTextPaint.getFontMetrics();
  float baseline =
      getHeight() / 2f - (fm.ascent + fm.descent) / 2f;

	float centerX = getWidth() / 2f;
	float lineSpacing = _spaceTrackpadTextPaint.getTextSize() * 1.1f;

	canvas.drawText(
		"↑",
		centerX,
		baseline - lineSpacing,
		_spaceTrackpadTextPaint);

	canvas.drawText(
		"←  Cursor  →",
		centerX,
		baseline,
		_spaceTrackpadTextPaint);

	canvas.drawText(
		"↓",
		centerX,
		baseline + lineSpacing,
		_spaceTrackpadTextPaint);
}

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (_tc.keyboard_background_paint != null)
    {
      canvas.drawRect(
              0,
              0,
              getWidth(),
              getHeight(),
              _tc.keyboard_background_paint);
    }

    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      for (KeyboardData.Key k : row.keys)
      {
        x += k.shift * _keyWidth;
        float keyW = _keyWidth * k.width - _tc.horizontal_margin;
        boolean isKeyDown = _pointers.isKeyDown(k);
        Theme.Computed.Key tc_key;
        if (isKeyDown)
          tc_key = _tc.key_activated;
        else
          switch (k.role)
          {
            case Number: tc_key = _tc.key_number; break;
            case Action: tc_key = _tc.key_action; break;
            case Space_bar: tc_key = _tc.key_space_bar; break;
            case Suggestion: tc_key = _tc.key_suggestion; break;
            default:
            case Normal: tc_key = _tc.key; break;
          }
        drawKeyFrame(canvas, x, y, keyW, keyH, tc_key);
        if (k.keys[0] != null)
          drawLabel(canvas, k.keys[0], keyW / 2f + x, y, keyH, isKeyDown, tc_key);
        for (int i = 1; i < 9; i++)
        {
          if (k.keys[i] != null)
            drawSubLabel(canvas, k.keys[i], x, y, keyW, keyH, i, isKeyDown, tc_key);
        }
        drawIndication(canvas, k, x, y, keyW, keyH, _tc);
        x += _keyWidth * k.width;
      }
      y += row.height * _tc.row_height;
    }

	// Draw the trackpad above the keyboard keys.
	drawSpaceTrackpad(canvas);

	// Draw the preview last so it stays above the keyboard keys.
	drawKeyPreview(canvas);
  }

  @Override
  public void onDetachedFromWindow()
  {
    clearKeyPreview();
    super.onDetachedFromWindow();
  }

  /** Draw borders and background of the key. */
  void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
      Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    float w = tc.border_width;
    float padding = w / 2.f;
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
    if (w > 0.f)
    {
      float overlap = r - r * 0.85f + w; // sin(45°)
      drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
      drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
      drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
      drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
    }
  }

  /** Clip to draw a border at a time. This allows to call [drawRoundRect]
      several time with the same parameters but a different Paint. */
  void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
      float clipb, Paint paint, Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    canvas.save();
    canvas.clipRect(clipl, clipt, clipr, clipb);
    canvas.drawRoundRect(_tmpRect, r, r, paint);
    canvas.restore();
  }

  private int labelColor(KeyValue k, boolean isKeyDown, boolean sublabel)
  {
    if (isKeyDown)
    {
      int flags = _pointers.getKeyFlags(k);
      if (flags != -1)
      {
        if ((flags & Pointers.FLAG_P_LOCKED) != 0)
          return _theme.lockedColor;
        return _theme.activatedColor;
      }
      return _theme.pressedColor;
    }
    if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY | KeyValue.FLAG_GREYED))
    {
      if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
        return _theme.greyedLabelColor;
      return _theme.secondaryLabelColor;
    }
    return sublabel ? _theme.subLabelColor : _theme.labelColor;
  }

  private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyH, boolean isKeyDown, Theme.Computed.Key tc)
  {
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, true);
    int color = (tc == _tc.key_suggestion)
      ? _theme.suggestionLabelColor : labelColor(kv, isKeyDown, false);
    Paint p = tc.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), color, textSize);
    canvas.drawText(kv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p);
  }

  private void drawSubLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyW, float keyH, int sub_index, boolean isKeyDown,
      Theme.Computed.Key tc)
  {
    Paint.Align a = LABEL_POSITION_H[sub_index];
    Vertical v = LABEL_POSITION_V[sub_index];
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, false);
    int color = (tc == _tc.key_suggestion)
      ? _theme.suggestionLabelColor : labelColor(kv, isKeyDown, true);
    Paint p = tc.sublabel_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), color, textSize, a);
    float subPadding = _config.keyPadding;
    if (v == Vertical.CENTER)
      y += (keyH - p.ascent() - p.descent()) / 2f;
    else
      y += (v == Vertical.TOP) ? subPadding - p.ascent() : keyH - subPadding - p.descent();
    if (a == Paint.Align.CENTER)
      x += keyW / 2f;
    else
      x += (a == Paint.Align.LEFT) ? subPadding : keyW - subPadding;
    String label = kv.getString();
    int label_len = label.length();
    // Limit the label of string keys to 3 characters
    if (label_len > 3 && kv.getKind() == KeyValue.Kind.String)
      label_len = 3;
    canvas.drawText(label, 0, label_len, x, y, p);
  }

  private void drawIndication(Canvas canvas, KeyboardData.Key k, float x,
      float y, float keyW, float keyH, Theme.Computed tc)
  {
    if (k.indication == null || k.indication.equals(""))
      return;
    Paint p = tc.indication_paint;
    p.setTextSize(_subLabelSize);
    canvas.drawText(k.indication, 0, k.indication.length(),
        x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4/5 + y, p);
  }

  private float scaleTextSize(KeyValue k, boolean main_label)
  {
    float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
    float label_size = main_label ? _mainLabelSize : _subLabelSize;
    return label_size * smaller_font;
  }
}
