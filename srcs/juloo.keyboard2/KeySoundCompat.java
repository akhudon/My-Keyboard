package juloo.keyboard2;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.view.View;

/** Lightweight key-press sound playback. */
public final class KeySoundCompat
{
  private final SoundPool _soundPool;
  private final int _soundId;
  private int _streamId = 0;

  public KeySoundCompat(Context context)
  {
    AudioAttributes attrs = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build();
    _soundPool = new SoundPool.Builder()
        .setAudioAttributes(attrs)
        .setMaxStreams(4)
        .build();
    _soundId = _soundPool.load(context, R.raw.key_click, 1);
  }

  public void play(View view, Config config)
  {
    if (!config.key_sound_enabled)
      return;
    if (_soundId == 0)
      return;
    try
    {
      float volume = Math.max(0f, Math.min(1f,
          Config.globalPrefs().getInt("key_sound_volume", 50) / 100f));
      _streamId = _soundPool.play(_soundId, volume, volume, 1, 0, 1.0f);
    }
    catch (Exception e) {}
  }

  public void release()
  {
    try
    {
      _soundPool.release();
    }
    catch (Exception e) {}
  }
}
