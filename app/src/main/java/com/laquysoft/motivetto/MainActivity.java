package com.laquysoft.motivetto;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.transition.AutoTransition;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionInflater;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.Player;
import com.google.android.gms.plus.Plus;
import com.google.example.games.basegameutils.BaseGameUtils;
import com.laquysoft.motivetto.data.StatsContract;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;

import org.w3c.dom.Text;


public class MainActivity extends FragmentActivity
        implements MainMenuFragment.Listener,
        GameplayFragment.Listener, WinFragment.Listener,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    public final static String LOG_TAG = MainActivity.class.getSimpleName();
    // Fragments
    MainMenuFragment mMainMenuFragment;
    GameplayFragment mGameplayFragment;
    WinFragment mWinFragment;
    StatsFragment mStatsFragment;

    // Client used to interact with Google APIs
    private GoogleApiClient mGoogleApiClient;

    // Are we currently resolving a connection failure?
    private boolean mResolvingConnectionFailure = false;

    // Has the user clicked the sign-in button?
    private boolean mSignInClicked = false;

    // Automatically start the sign-in flow when the Activity starts
    private boolean mAutoStartSignInFlow = true;

    // request codes we use when invoking an external activity
    private static final int RC_RESOLVE = 5000;
    private static final int RC_UNUSED = 5001;
    private static final int RC_SIGN_IN = 9001;

    // tag for debug logging
    final boolean ENABLE_DEBUG = true;
    final String TAG = "TanC";


    // playing on hard mode?
    boolean mHardMode = false;

    // achievements and scores we're pending to push to the cloud
    // (waiting for the user to sign in, for instance)
    AccomplishmentsOutbox mOutbox = new AccomplishmentsOutbox();


    // Request code will be used to verify if result comes from the login activity. Can be set to any integer.
    private static final int REQUEST_CODE = 1337;
    private static final String REDIRECT_URI = "yourcustomprotocol://callback";

    private String token;
    private boolean runningGame = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create the Google API Client with access to Plus and Games
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Plus.API).addScope(Plus.SCOPE_PLUS_LOGIN)
                .addApi(Games.API).addScope(Games.SCOPE_GAMES)
                .build();

        if (savedInstanceState == null) {
           // create fragments
            mMainMenuFragment = new MainMenuFragment();
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, mMainMenuFragment, "MainMenuFragment").commit();
        } else {
             mMainMenuFragment = (MainMenuFragment) getSupportFragmentManager().findFragmentByTag("MainMenuFragment");
        }

        mGameplayFragment = new GameplayFragment();
        mWinFragment = new WinFragment();
        mStatsFragment = new StatsFragment();

        // listen to fragment events
        mMainMenuFragment.setListener(this);
        mGameplayFragment.setListener(this);
        mWinFragment.setListener(this);


        // load outbox from file
        mOutbox.loadLocal(this);
    }

    // Switch UI to the given fragment
    void switchToFragment(Fragment newFrag) {
        getSupportFragmentManager().popBackStack(mMainMenuFragment.getClass().getName(), FragmentManager.POP_BACK_STACK_INCLUSIVE);


        // Check that the device is running lollipop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Inflate transitions to apply
            Transition changeTransform = TransitionInflater.from(this).
                    inflateTransition(R.transition.transition_button);
            Transition explodeTransform = TransitionInflater.from(this).
                    inflateTransition(android.R.transition.slide_bottom);
            Transition enterTransofrm = TransitionInflater.from(this).
                    inflateTransition(android.R.transition.slide_right);

            // Setup exit transition on first fragment
            mMainMenuFragment.setSharedElementReturnTransition(changeTransform);
            mMainMenuFragment.setExitTransition(explodeTransform);

            // Setup enter transition on second fragment
            newFrag.setSharedElementEnterTransition(changeTransform);
            newFrag.setEnterTransition(enterTransofrm);

            // Find the shared element (in Fragment A)
            TextView ivProfile = (TextView) findViewById(R.id.progress_txt);

            // Add second fragment by replacing first
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, newFrag)
                    .addToBackStack(mMainMenuFragment.getClass().getName())
                    .addSharedElement(ivProfile, "profile");
            // Apply the transaction
            ft.commit();
        }


    }

    private boolean isSignedIn() {
        return (mGoogleApiClient != null && mGoogleApiClient.isConnected());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart(): connecting");
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop(): disconnecting");
        runningGame = false;
        MediaPlayerService.pauseTrack(this);

        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    @Override
    public void onStartGameRequested(boolean hardMode) {
        startGame(hardMode);
    }

    @Override
    public void onShowAchievementsRequested() {
        if (isSignedIn()) {
            startActivityForResult(Games.Achievements.getAchievementsIntent(mGoogleApiClient),
                    RC_UNUSED);
        } else {
            BaseGameUtils.makeSimpleDialog(this, getString(R.string.achievements_not_available)).show();
        }
    }

    @Override
    public void onShowLeaderboardsRequested() {
        if (isSignedIn()) {
            startActivityForResult(Games.Leaderboards.getAllLeaderboardsIntent(mGoogleApiClient),
                    RC_UNUSED);
        } else {
            BaseGameUtils.makeSimpleDialog(this, getString(R.string.leaderboards_not_available)).show();
        }
    }

    /**
     * Start gameplay. This means updating some status variables and switching
     * to the "gameplay" screen (the screen where the user types the score they want).
     *
     * @param hardMode whether to start gameplay in "hard mode".
     */
    void startGame(boolean hardMode) {
        mHardMode = hardMode;
        onSetMode(mHardMode);
        switchToFragment(mGameplayFragment);
    }

    @Override
    public void onEnteredScore() {

        runningGame = true;
        String trackName = mGameplayFragment.getTrackName();
        String trackArtist = mGameplayFragment.getTrackArtist();
        int solvedTime = mGameplayFragment.stopTimer();
        int solvedMoves = mGameplayFragment.getSolvedMoves();

        int requestedScore = 10000 / (solvedTime + solvedMoves);

        // Compute final score (in easy mode, it's the requested score; in hard mode, it's double)
        int finalScore = mHardMode ? requestedScore * 2 : requestedScore;

        addStat(trackName, trackArtist, solvedTime, solvedMoves, mHardMode);

        updateWidgets();

        mWinFragment.setFinalScore(finalScore);
        mWinFragment.setExplanation(mHardMode ? getString(R.string.hard_mode_explanation) :
                getString(R.string.easy_mode_explanation));

        // check for achievements
        checkForAchievements(requestedScore, finalScore);

        // update leaderboards
        updateLeaderboards(finalScore);

        // push those accomplishments to the cloud, if signed in
        pushAccomplishments();

        // switch to the exciting "you won" screen
        switchToFragment(mWinFragment);
    }

    private void updateWidgets() {
        // Setting the package ensures that only components in our app will receive the broadcast
        Intent dataUpdatedIntent = new Intent(StatsContract.ACTION_STATS_DATA_UPDATED)
                .setPackage(getPackageName());
        sendBroadcast(dataUpdatedIntent);
    }


    /**
     * Helper method to handle insertion of a new stat in the stats database.
     */
    long addStat(String trackName, String trackArtist, int solvedTime, int solvedMoves, boolean isHardMode) {
        long statId;


        // Now that the content provider is set up, inserting rows of data is pretty simple.
        // First create a ContentValues object to hold the data you want to insert.
        ContentValues locationValues = new ContentValues();

        // Then add the data, along with the corresponding name of the data type,
        // so the content provider knows what kind of value is being inserted.
        locationValues.put(StatsContract.StatsEntry.COLUMN_PLAYER_NAME, Games.Players.getCurrentPlayer(mGoogleApiClient).getDisplayName());
        locationValues.put(StatsContract.StatsEntry.COLUMN_TRACK_NAME, trackName);
        locationValues.put(StatsContract.StatsEntry.COLUMN_TRACK_ARTIST, trackArtist);
        locationValues.put(StatsContract.StatsEntry.COLUMN_TRACK_SOLVED_TIME, solvedTime);
        locationValues.put(StatsContract.StatsEntry.COLUMN_TRACK_SOLVED_MOVES, solvedMoves);
        locationValues.put(StatsContract.StatsEntry.COLUMN_TRACK_HARD_MODE, (isHardMode) ? 1 : 0);

        // Finally, insert stat data into the database.
        Uri insertedUri = getContentResolver().insert(
                StatsContract.StatsEntry.CONTENT_URI,
                locationValues
        );

        // The resulting URI contains the ID for the row.  Extract the statId from the Uri.
        statId = ContentUris.parseId(insertedUri);


        // Wait, that worked?  Yes!
        return statId;
    }

    @Override
    public String onAccessToken() {
        return token;
    }

    @Override
    public void onIncrementMoves(int moves) {
        mGameplayFragment.incrementMovesNumber(moves);
    }

    @Override
    public void onSetMode(boolean mode) {
        mGameplayFragment.setMode(mode);
    }

    // Checks if n is prime. We don't consider 0 and 1 to be prime.
    // This is not an implementation we are mathematically proud of, but it gets the job done.
    boolean isPrime(int n) {
        int i;
        if (n == 0 || n == 1) return false;
        for (i = 2; i <= n / 2; i++) {
            if (n % i == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check for achievements and unlock the appropriate ones.
     *
     * @param requestedScore the score the user requested.
     * @param finalScore     the score the user got.
     */
    void checkForAchievements(int requestedScore, int finalScore) {
        // Check if each condition is met; if so, unlock the corresponding
        // achievement.
        if (isPrime(finalScore)) {
            mOutbox.mPrimeAchievement = true;
            achievementToast(getString(R.string.achievement_prime_toast_text));
        }
        if (requestedScore == 9999) {
            mOutbox.mArrogantAchievement = true;
            achievementToast(getString(R.string.achievement_arrogant_toast_text));
        }
        if (requestedScore == 0) {
            mOutbox.mHumbleAchievement = true;
            achievementToast(getString(R.string.achievement_humble_toast_text));
        }
        if (finalScore == 1337) {
            mOutbox.mLeetAchievement = true;
            achievementToast(getString(R.string.achievement_leet_toast_text));
        }
        mOutbox.mBoredSteps++;
    }

    void unlockAchievement(int achievementId, String fallbackString) {
        if (isSignedIn()) {
            Games.Achievements.unlock(mGoogleApiClient, getString(achievementId));
        } else {
            Toast.makeText(this, getString(R.string.achievement) + ": " + fallbackString,
                    Toast.LENGTH_LONG).show();
        }
    }

    void achievementToast(String achievement) {
        // Only show toast if not signed in. If signed in, the standard Google Play
        // toasts will appear, so we don't need to show our own.
        if (!isSignedIn()) {
            Toast.makeText(this, getString(R.string.achievement) + ": " + achievement,
                    Toast.LENGTH_LONG).show();
        }
    }

    void pushAccomplishments() {
        if (!isSignedIn()) {
            // can't push to the cloud, so save locally
            mOutbox.saveLocal(this);
            return;
        }
        if (mOutbox.mPrimeAchievement) {
            Games.Achievements.unlock(mGoogleApiClient, getString(R.string.achievement_prime));
            mOutbox.mPrimeAchievement = false;
        }
        if (mOutbox.mArrogantAchievement) {
            Games.Achievements.unlock(mGoogleApiClient, getString(R.string.achievement_arrogant));
            mOutbox.mArrogantAchievement = false;
        }
        if (mOutbox.mHumbleAchievement) {
            Games.Achievements.unlock(mGoogleApiClient, getString(R.string.achievement_humble));
            mOutbox.mHumbleAchievement = false;
        }
        if (mOutbox.mLeetAchievement) {
            Games.Achievements.unlock(mGoogleApiClient, getString(R.string.achievement_leet));
            mOutbox.mLeetAchievement = false;
        }
        if (mOutbox.mBoredSteps > 0) {
            Games.Achievements.increment(mGoogleApiClient, getString(R.string.achievement_really_bored),
                    mOutbox.mBoredSteps);
            Games.Achievements.increment(mGoogleApiClient, getString(R.string.achievement_bored),
                    mOutbox.mBoredSteps);
        }
        if (mOutbox.mEasyModeScore >= 0) {
            Games.Leaderboards.submitScore(mGoogleApiClient, getString(R.string.leaderboard_easy),
                    mOutbox.mEasyModeScore);
            mOutbox.mEasyModeScore = -1;
        }
        if (mOutbox.mHardModeScore >= 0) {
            Games.Leaderboards.submitScore(mGoogleApiClient, getString(R.string.leaderboard_hard),
                    mOutbox.mHardModeScore);
            mOutbox.mHardModeScore = -1;
        }
        mOutbox.saveLocal(this);
    }

    /**
     * Update leaderboards with the user's score.
     *
     * @param finalScore The score the user got.
     */
    void updateLeaderboards(int finalScore) {
        if (mHardMode && mOutbox.mHardModeScore < finalScore) {
            mOutbox.mHardModeScore = finalScore;
        } else if (!mHardMode && mOutbox.mEasyModeScore < finalScore) {
            mOutbox.mEasyModeScore = finalScore;
        }
    }

    @Override
    public void onWinScreenDismissed() {

        if (runningGame) {
            switchToFragment(mGameplayFragment);
        } else {
            switchToFragment(mMainMenuFragment);

        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);

            switch (response.getType()) {
                // Response was successful and contains auth token
                case TOKEN:
                    // Handle successful response
                    token = response.getAccessToken();
                    break;

                // Auth flow returned an error
                case ERROR:
                    // Handle error response
                    break;

                // Most likely auth flow was cancelled
                default:
                    // Handle other cases
            }
        } else if (requestCode == RC_SIGN_IN) {
            mSignInClicked = false;
            mResolvingConnectionFailure = false;
            if (resultCode == RESULT_OK) {
                mGoogleApiClient.connect();
            } else {
                BaseGameUtils.showActivityResultError(this, requestCode, resultCode, R.string.signin_other_error);
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected(): connected to Google APIs");
        // Show sign-out button on main menu
        mMainMenuFragment.setShowSignInButton(false);

        // Set the greeting appropriately on main menu
        Player p = Games.Players.getCurrentPlayer(mGoogleApiClient);
        String displayName;
        if (p == null) {
            Log.w(TAG, "mGamesClient.getCurrentPlayer() is NULL!");
            displayName = "???";
        } else {
            displayName = p.getDisplayName();
        }
        mMainMenuFragment.setGreeting("Hello, " + displayName);


        // if we have accomplishments to push, push them
        if (!mOutbox.isEmpty()) {
            pushAccomplishments();
            Toast.makeText(this, getString(R.string.your_progress_will_be_uploaded),
                    Toast.LENGTH_LONG).show();
        }

        AuthenticationRequest.Builder builder =
                new AuthenticationRequest.Builder(getResources().getString(R.string.spotify_client_id), AuthenticationResponse.Type.TOKEN, REDIRECT_URI);

        builder.setScopes(new String[]{"streaming"});
        AuthenticationRequest request = builder.build();

        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "onConnectionSuspended(): attempting to connect");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "onConnectionFailed(): attempting to resolve");
        if (mResolvingConnectionFailure) {
            Log.d(TAG, "onConnectionFailed(): already resolving");
            return;
        }

        if (mSignInClicked || mAutoStartSignInFlow) {
            mAutoStartSignInFlow = false;
            mSignInClicked = false;
            mResolvingConnectionFailure = true;
            if (!BaseGameUtils.resolveConnectionFailure(this, mGoogleApiClient, connectionResult,
                    RC_SIGN_IN, getString(R.string.signin_other_error))) {
                mResolvingConnectionFailure = false;
            }
        }

        // Sign-in failed, so show sign-in button on main menu
        mMainMenuFragment.setGreeting(getString(R.string.signed_out_greeting));
        mMainMenuFragment.setShowSignInButton(true);
    }

    @Override
    public void onSignInButtonClicked() {
        // Check to see the developer who's running this sample code read the instructions :-)
        // NOTE: this check is here only because this is a sample! Don't include this
        // check in your actual production app.
        if (!BaseGameUtils.verifySampleSetup(this, R.string.app_id,
                R.string.achievement_prime, R.string.leaderboard_easy)) {
            Log.w(TAG, "*** Warning: setup problems detected. Sign in may not work!");
        }

        // start the sign-in flow
        mSignInClicked = true;
        mGoogleApiClient.connect();
    }

    @Override
    public void onSignOutButtonClicked() {
        mSignInClicked = false;
        Games.signOut(mGoogleApiClient);
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }

        mMainMenuFragment.setGreeting(getString(R.string.signed_out_greeting));
        mMainMenuFragment.setShowSignInButton(true);
    }

    @Override
    public void onShowStatsButtonClicked() {

        switchToFragment(mStatsFragment);

    }

    class AccomplishmentsOutbox {
        boolean mPrimeAchievement = false;
        boolean mHumbleAchievement = false;
        boolean mLeetAchievement = false;
        boolean mArrogantAchievement = false;
        int mBoredSteps = 0;
        int mEasyModeScore = -1;
        int mHardModeScore = -1;

        boolean isEmpty() {
            return !mPrimeAchievement && !mHumbleAchievement && !mLeetAchievement &&
                    !mArrogantAchievement && mBoredSteps == 0 && mEasyModeScore < 0 &&
                    mHardModeScore < 0;
        }

        public void saveLocal(Context ctx) {
            /* TODO: This is left as an exercise. To make it more difficult to cheat,
             * this data should be stored in an encrypted file! And remember not to
             * expose your encryption key (obfuscate it by building it from bits and
             * pieces and/or XORing with another string, for instance). */
        }

        public void loadLocal(Context ctx) {
            /* TODO: This is left as an exercise. Write code here that loads data
             * from the file you wrote in saveLocal(). */
        }

    }


    public boolean isHardMode() {
        return mHardMode;
    }

    @Override
    public void onWinScreenSignInClicked() {
        mSignInClicked = true;
        mGoogleApiClient.connect();
    }
}
