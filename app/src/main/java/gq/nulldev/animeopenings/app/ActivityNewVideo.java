package gq.nulldev.animeopenings.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Html;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.json.JSONException;
import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.MediaPlayer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import gq.nulldev.animeopenings.app.util.ConcurrencyUtils;
import gq.nulldev.animeopenings.app.util.SubtitleSeeker;
import gq.nulldev.animeopenings.app.util.TutorialView;
import subtitleFile.TimedTextObject;

public class ActivityNewVideo extends Activity implements IVLCVout.Callback {
    public final static String TAG = "AnimeOpenings";
    public final static Locale LOCALE = Locale.US;

    public final static int SEEK_BAR_ACCURACY = 10000;

    //UI Elements
    private ProgressBar bufferIndicator;
    private ImageButton settingsButton;
    private ImageButton shareButton;
    private LinearLayout controlsBar;
    private TextView videoRangeText;
    private SeekBar seekBar;
    private ImageButton playPauseButton;
    private SurfaceView surfaceView;
    private TextView songInfo;
    private TextView subtitleTextView;
    private LinearLayout topButtonBar;

    private int mVideoWidth;
    private int mVideoHeight;

    //Music service
    MediaService mediaService;
    ServiceConnection serviceConnection;

    //Controls
    public boolean controlsShowing = true;

    //Handler
    private Handler handler;

    //Instance
    public static ActivityNewVideo INSTANCE;

    //Played videos
    public ArrayList<Video> videos;

    //Gesture detector
    GestureDetector gestureDetector;

    //Subtitles seeker
    SubtitleSeeker subtitleSeeker;

    public final static int PLAY_ICON = android.R.drawable.ic_media_play;
    public final static int PAUSE_ICON = android.R.drawable.ic_media_pause;

    float positionBackup = -1;

    HideControlsTask hideControlsTask;

    public SharedPreferences preferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Inflate XML
        setContentView(R.layout.activity_nv);

        INSTANCE = this;

        //Get shared prefs
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        //Setup gesture detector
        gestureDetector = new GestureDetector(this, new GestureListener(this));

        //Landscape orientation please
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        //Setup handler
        handler = new Handler(Looper.getMainLooper());

        //Assign ui elements
        bufferIndicator = (ProgressBar) findViewById(R.id.bufferIndicator);
        settingsButton = (ImageButton) findViewById(R.id.btnSettings);
        shareButton = (ImageButton) findViewById(R.id.btnShare);
        controlsBar = (LinearLayout) findViewById(R.id.lowerBtnBar);
        videoRangeText = (TextView) findViewById(R.id.pbRange);
        seekBar = (SeekBar) findViewById(R.id.seekBar);
        playPauseButton = (ImageButton) findViewById(R.id.btnPlayPause);
        surfaceView = (SurfaceView) findViewById(R.id.fullscreen_video);
        songInfo = (TextView) findViewById(R.id.songInfo);
        subtitleTextView = (TextView) findViewById(R.id.subTextView);
        topButtonBar = (LinearLayout) findViewById(R.id.topBtnBar);

        //Default seek bar to 00:00/00:00
        updatePlaybackRangeText(0, 0);

        //Set seekbar max to accuracy
        updateSeekMax(SEEK_BAR_ACCURACY);

        //Assign actions to buttons
        //Open settings on settings button click
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        //Open openings.moe link on click
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(ActivityNewVideo.this).setTitle(R.string.activity_nv_share_title)
                        .setItems(new CharSequence[]{getString(R.string.activity_nv_share_op_1), getString(R.string.activity_nv_share_op_2)}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                if (mediaService != null && mediaService.getCurrentVideo() != null) {
                                    String openingsMoeUrl = mediaService.getCurrentVideo().getBrowserUrl();

                                    switch (which) {
                                        case 0:
                                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(openingsMoeUrl));
                                            startActivity(browserIntent);
                                            break;
                                        case 1:
                                            Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                                            sharingIntent.setType("text/plain");
                                            sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.activity_nv_share_subject));
                                            sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, String.format(getString(R.string.activity_nv_share_body), openingsMoeUrl));
                                            startActivity(Intent.createChooser(sharingIntent, getString(R.string.activity_nv_share_chooser)));
                                            break;
                                    }
                                }
                            }
                        }).show();
            }
        });

        //Make subtitle seeker
        subtitleSeeker = new SubtitleSeeker(null, subtitleTextView);

        //Gesture listener
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    toggleControls();
                }
                return true;
            }
        });

        //Allow seek bar to actually seek
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaService.getPlayer() != null) {
                    mediaService.getPlayer().setPosition(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        //Setup mediaplayer
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                holder.setKeepScreenOn(true);
                if (mediaService != null && mediaService.getPlayer() != null) {
                    Log.i(TAG, "Surface re-created, restoring MediaPlayer state!");
                    new Thread(new Runnable() {
                        @Override public void run() {
                            //Recreate entire mediaplayer
                            mediaService.getPlayer().pause();
                            //Request that the position be restored on next rebuilding
                            positionBackup = mediaService.getPlayer().getPosition();
                            mediaService.playVideo(mediaService.getCurrentVideo());
                        }
                    }).start();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mediaService != null && mediaService.getPlayer() != null) {
//                    releaseVoutView(mediaService.getPlayer());
                }
            }
        });

        //Play pause video
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mediaService.doPlayPause();
                updatePlayPauseButton();
            }
        });
    }

    void bindVoutView(MediaPlayer player) {
        final IVLCVout vout = player.getVLCVout();
//        vout.setVideoView(surfaceView);
        vout.setVideoSurface(surfaceView.getHolder().getSurface(), surfaceView.getHolder());
        //TODO
        //vout.setSubtitlesView(mSurfaceSubtitles);
        vout.attachViews();
    }

    void releaseVoutView(MediaPlayer player) {
        player.getVLCVout().detachViews();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setSize(mVideoWidth, mVideoHeight);
    }

    private void setSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        if (mVideoWidth * mVideoHeight <= 1)
            return;

        if(surfaceView == null || surfaceView.getHolder() == null)
            return;

        // get screen size
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();

        // getWindow().getDecorView() doesn't always take orientation into
        // account, we have to correct the values
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (w > h && isPortrait || w < h && !isPortrait) {
            int i = w;
            w = h;
            h = i;
        }

        float videoAR = (float) mVideoWidth / (float) mVideoHeight;
        float screenAR = (float) w / (float) h;

        if (screenAR < videoAR)
            h = (int) (w / videoAR);
        else
            w = (int) (h * videoAR);

        // force surface buffer size
        surfaceView.getHolder().setFixedSize(mVideoWidth, mVideoHeight);

        // set display size
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) surfaceView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        surfaceView.setLayoutParams(lp);
        surfaceView.invalidate();
    }


    void bindServices() {
        if (mediaService == null) {
            Intent intent = new Intent(this, MediaService.class);
            serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    mediaService = ((MediaService.MediaBinder) service).getService();
                    mediaService.setOnMediaPlayerBuiltListener(new MediaService.OnMediaPlayerBuiltListener() {
                        @Override
                        public void onMediaPlayerBuilt(final MediaPlayer mp) {
                            bindVoutView(mp);
                            mp.getVLCVout().addCallback(ActivityNewVideo.this);
                            mediaService.setOnMediaPlayerReleasedListener(new MediaService.OnMediaPlayerReleasedListener() {
                                @Override public void onMediaPlayerReleased(MediaPlayer player) {
                                    player.getVLCVout().removeCallback(ActivityNewVideo.this);
                                }
                            });
                            mp.setEventListener(new MediaPlayer.EventListener() {
                                @Override public void onEvent(MediaPlayer.Event event) {
                                    switch (event.type) {
                                        case MediaPlayer.Event.Opening:
                                            bufferIndicator.setVisibility(View.VISIBLE);
                                            break;
                                        case MediaPlayer.Event.Playing:
                                            //Restore position backup if required
                                            if(positionBackup != -1) {
                                                mediaService.getPlayer().setPosition(positionBackup);
                                                positionBackup = -1;
                                            }
                                            //Hide buffer loading indicator
                                            bufferIndicator.setVisibility(View.GONE);
                                            updateTrackString(mediaService.getCurrentVideo(), getVideoDetails(mediaService.getCurrentVideo(), true));
                                            break;
                                        case MediaPlayer.Event.EndReached:
                                            if (preferences.getBoolean("prefLoopVideo", false)) {
                                                mp.setPosition(0);
                                                mp.play();
                                            } else {
                                                playNextVideo();
                                            }
                                            break;
                                    }
                                }
                            });
                        }
                    });
                    //Finish activity on notification dismiss
                    mediaService.setOnStopListener(new Runnable() {
                        @Override public void run() {
                            finish();
                        }
                    });
                    mediaService.setupService(videos, subtitleSeeker, PreferenceManager.getDefaultSharedPreferences(ActivityNewVideo.this));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mediaService.getPlayer() != null && mediaService.isPlaying()) {
//                            Update seek bar
                                updateSeekPlayed(mediaService.getPlayer().getPosition());
//                            Update played
                                updatePlaybackRangeText((int) mediaService.getPositionMS(),
                                        (int) mediaService.getPlayer().getLength());
                            }
                            handler.postDelayed(this, 500);
                        }
                    });
                    playNextVideo();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.w("AnimeOpenings", "MediaService disconnected!");
                }
            };
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    public void updateTrackString(Video vid, VideoDetails details) {
        String trackString = "<b>" + vid.getSource() + "</b><br/>" + vid.getName();
        if (details != null && details.getSubtitles() != null) {
            trackString += "<br/>Subtitler: " + details.getSubtitles();
        }
        songInfo.setText(Html.fromHtml(trackString));
        showControls();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        buildVideos();
    }

    void updatePlayPauseButton() {
        if (mediaService.isPaused()) {
            playPauseButton.setImageDrawable(getResources().getDrawable(PLAY_ICON));
        } else {
            playPauseButton.setImageDrawable(getResources().getDrawable(PAUSE_ICON));
        }
    }

    void updateSeekMax(int max) {
        seekBar.setMax(max);
    }

    //Not working in VLC
    void updateSeekBuffered(int buffered) {
        int amountBuffered = buffered;
        //Division by zero protection
        if (amountBuffered < 1)
            amountBuffered = 1;
        seekBar.setSecondaryProgress((amountBuffered / 100) * seekBar.getMax());
    }

    void updateSeekPlayed(float percent) {
        if (mediaService.getPlayer() != null) {
            seekBar.setProgress((int) (percent * SEEK_BAR_ACCURACY));
        }
    }

    void updatePlaybackRangeText(int cur, int max) {
        videoRangeText.setText(formatMs(cur) + "/" + formatMs(max));
    }

    void openSettings() {
        Intent openSettingsIntent = new Intent(this, SettingsActivity.class);
        startActivity(openSettingsIntent);
    }

    public void buildVideos() {
        new GetVideosTask(this).execute();
    }

    void toggleControls() {
        if (controlsBar.getVisibility() == View.VISIBLE) {
            hideControls();
        } else {
            showControls();
        }
    }

    void showControls() {
        //Cancel any previous hide tasks
        if (hideControlsTask != null) {
            hideControlsTask.cancel();
        }
        songInfo.setAlpha(1);
        songInfo.setVisibility(View.VISIBLE);
        songInfo.invalidate();
        controlsBar.setAlpha(1);
        controlsBar.setVisibility(View.VISIBLE);
        controlsBar.invalidate();
        topButtonBar.setAlpha(1);
        topButtonBar.setVisibility(View.VISIBLE);
        topButtonBar.invalidate();
        hideControlsTask = new HideControlsTask(controlsBar, songInfo, topButtonBar);
        handler.postDelayed(hideControlsTask, 1500);
        controlsShowing = true;
    }

    void hideControls() {
        if (hideControlsTask != null) {
            hideControlsTask.cancel();
        }
        songInfo.setAlpha(0);
        songInfo.setVisibility(View.GONE);
        songInfo.invalidate();
        controlsBar.setAlpha(0);
        controlsBar.setVisibility(View.GONE);
        controlsBar.invalidate();
        topButtonBar.setAlpha(0);
        topButtonBar.setVisibility(View.GONE);
        topButtonBar.invalidate();
        controlsShowing = false;
    }

    void playPrevVideo() {
        if (!mediaService.doPrev()) {
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ActivityNewVideo.this, "No previous videos to play!", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            afterPlayVideo();
        }
    }

    public void playNextVideo() {
        mediaService.playNextVideo();
        afterPlayVideo();
    }

    void afterPlayVideo() {
        bufferIndicator.setVisibility(View.VISIBLE);
        //Enable subtitles if possible
        if (preferences.getBoolean("prefSubtitles", true)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    goSubtitles(mediaService.getCurrentVideo());
                }
            }, "AnimeOpenings > Subtitle DP").start();
        }
        updatePlayPauseButton();
    }

    HashMap<String, VideoDetails> detailsCache = new HashMap<>();
    OkHttpClient client = new OkHttpClient();

    public VideoDetails getVideoDetails(Video vid, boolean cacheOnly) {
        String detailsURL;
        try {
            detailsURL = "http://openings.moe/api/details.php?file=" + URLEncoder.encode(vid.getFile(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException();
        }

        VideoDetails out;
        if (detailsCache.containsKey(detailsURL)) {
            out = detailsCache.get(detailsURL);
        } else {
            if(cacheOnly) {
                return null;
            }
            try {
                Response response = client.newCall(new Request.Builder().url(detailsURL).build()).execute();
                out = VideoDetails.fromJSON(response.body().string());
            } catch (IOException e) {
                Log.w(TAG, "Video details download error!", e);
                return null;
            }
            if (out != null)
                detailsCache.put(detailsURL, out);
        }
        return out;
    }

    public void goSubtitles(final Video vid) {
        final VideoDetails details = getVideoDetails(vid, false);
        if(details != null && details.getSubtitles() != null) {
            try {
                Log.i(TAG, "Preparing subtitles for video: " + vid.getFileURL());
                final TimedTextObject converted = Convert.downloadAndParseSubtitle(vid.getSubtitleURL(), vid.getFilenameSplit(), getCacheDir());
                if (converted == null) {
                    throw new IOException("Subtitles are null!");
                }
                subtitleSeeker.sync(converted);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        updateTrackString(vid, details);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Subtitle parse/download error!", t);
            }
        }
    }

    public void onSwipeRight() {
        positionBackup = -1;
        playNextVideo();
    }

    public void onSwipeLeft() {
        positionBackup = -1;
        playPrevVideo();
    }

    String formatMs(int ms) {
        return String.format(LOCALE, "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(ms),
                TimeUnit.MILLISECONDS.toSeconds(ms) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(ms))
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        INSTANCE = null;
        if (serviceConnection != null) {
            unbindService(serviceConnection);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        //Show tutorial if required
        if(preferences.getBoolean(TutorialView.PREF_SHOW_CONTROLS_TUTORIAL, true)) {
            final TutorialView tutorialView = (TutorialView) findViewById(R.id.tutorialView);
            tutorialView.setOnDoneListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    tutorialView.setVisibility(View.GONE);
                    preferences.edit().putBoolean(TutorialView.PREF_SHOW_CONTROLS_TUTORIAL, false).apply();
                }
            });
            tutorialView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onNewLayout(IVLCVout vlcVout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0)
            return;

        // store video size
        mVideoWidth = width;
        mVideoHeight = height;
        setSize(mVideoWidth, mVideoHeight);
    }

    @Override public void onSurfacesCreated(IVLCVout vlcVout) {

    }

    @Override public void onSurfacesDestroyed(IVLCVout vlcVout) {

    }
}


final class GestureListener extends GestureDetector.SimpleOnGestureListener {

    ActivityNewVideo activityNewVideo;

    private static final int SWIPE_THRESHOLD = 200;
    private static final int SWIPE_VELOCITY_THRESHOLD = 200;

    public GestureListener(ActivityNewVideo activityNewVideo) {
        this.activityNewVideo = activityNewVideo;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        boolean result = false;
        try {
            float diffY = e2.getY() - e1.getY();
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        activityNewVideo.onSwipeRight();
                    } else {
                        activityNewVideo.onSwipeLeft();
                    }
                    result = true;
                }
            }
//            else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
//                if (diffY > 0) {
//                    MainActivity.INSTANCE.onSwipeBottom();
//                } else {
//                    MainActivity.INSTANCE.onSwipeTop();
//                }
//            }
            result = true;

        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return result;
    }
}

class HideControlsTask implements Runnable {

    boolean canceled;
    LinearLayout controlsBar;
    TextView songInfo;
    LinearLayout topButtonBar;

    public HideControlsTask(LinearLayout controlsBar, TextView songInfo, LinearLayout topButtonBar) {
        this.controlsBar = controlsBar;
        this.songInfo = songInfo;
        this.topButtonBar = topButtonBar;
    }

    @Override
    public void run() {
        if (!canceled) {
            animateView(controlsBar);
            animateView(songInfo);
            animateView(topButtonBar);
        }
        controlsBar = null;
        songInfo = null;
        topButtonBar = null;
    }

    void animateView(final View view) {
        final AlphaAnimation anim = new AlphaAnimation(1.0f, 0.0f);
        anim.setDuration(1000);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (!canceled) {
                    view.setAlpha(0);
                    view.setVisibility(View.GONE);
                    view.invalidate();
                    anim.reset();
                }
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        view.startAnimation(anim);
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public void cancel() {
        this.canceled = true;
    }
}

class GetVideosTask extends AsyncTask<Void, Void, ArrayList<Video>> {

    ProgressDialog dialog;
    ActivityNewVideo activityNewVideo;

    public GetVideosTask(ActivityNewVideo activityNewVideo) {
        this.activityNewVideo = activityNewVideo;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        dialog = ProgressDialog.show(activityNewVideo, "Refreshing Video List", "We are getting the latest openings from openings.moe!", true, false);
    }

    @Override
    protected void onPostExecute(ArrayList<Video> videos) {
        super.onPostExecute(videos);
        if (videos != null) {
//            Log.d(LOG_TAG, Arrays.toString(videos.toArray()));
            dialog.dismiss();
            activityNewVideo.videos = videos;
//            ConcurrencyUtils.runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
//                    activityNewVideo.playNextVideo();
//                }
//            });
            activityNewVideo.bindServices();
        }
    }

    @Override
    protected ArrayList<Video> doInBackground(Void... params) {
        try {
            return Video.getAvailableVideos(activityNewVideo);
        } catch (IOException | JSONException e) {
            Log.e(ActivityNewVideo.TAG, "Server contact failed!");
            ConcurrencyUtils.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activityNewVideo, "Could not contact openings server, are you offline?", Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    activityNewVideo.finish();
                }
            });
            e.printStackTrace();
        }
        return null;
    }
}