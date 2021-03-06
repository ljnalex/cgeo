package cgeo.geocaching.network;

import butterknife.InjectView;

import cgeo.geocaching.R;
import cgeo.geocaching.activity.AbstractActivity;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.MatcherWrapper;

import ch.boye.httpclientandroidlib.ParseException;
import ch.boye.httpclientandroidlib.client.entity.UrlEncodedFormEntity;
import ch.boye.httpclientandroidlib.util.EntityUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.util.regex.Pattern;

public abstract class OAuthAuthorizationActivity extends AbstractActivity {

    public static final int NOT_AUTHENTICATED = 0;
    public static final int AUTHENTICATED = 1;

    @NonNull final private String host;
    @NonNull final private String pathRequest;
    @NonNull final private String pathAuthorize;
    @NonNull final private String pathAccess;
    private final boolean https;
    @NonNull final private String consumerKey;
    @NonNull final private String consumerSecret;
    @NonNull final private String callback;
    private String OAtoken = null;
    private String OAtokenSecret = null;
    private final Pattern paramsPattern1 = Pattern.compile("oauth_token=([a-zA-Z0-9\\-\\_.]+)");
    private final Pattern paramsPattern2 = Pattern.compile("oauth_token_secret=([a-zA-Z0-9\\-\\_.]+)");
    @InjectView(R.id.start) protected Button startButton;
    @InjectView(R.id.auth_1) protected TextView auth_1;
    @InjectView(R.id.auth_2) protected TextView auth_2;
    private ProgressDialog requestTokenDialog = null;
    private ProgressDialog changeTokensDialog = null;
    private Handler requestTokenHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (requestTokenDialog != null && requestTokenDialog.isShowing()) {
                requestTokenDialog.dismiss();
            }

            startButton.setOnClickListener(new StartListener());
            startButton.setEnabled(true);

            if (msg.what == 1) {
                startButton.setText(getAuthAgain());
            } else {
                showToast(getErrAuthInitialize());
                startButton.setText(getAuthStart());
            }
        }

    };
    private Handler changeTokensHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (changeTokensDialog != null && changeTokensDialog.isShowing()) {
                changeTokensDialog.dismiss();
            }

            if (msg.what == AUTHENTICATED) {
                showToast(getAuthDialogCompleted());
                setResult(RESULT_OK);
                finish();
            } else {
                showToast(getErrAuthProcess());
                startButton.setText(getAuthStart());
            }
        }
    };

    public OAuthAuthorizationActivity
            (@NonNull String host,
             @NonNull String pathRequest,
             @NonNull String pathAuthorize,
             @NonNull String pathAccess,
             boolean https,
             @NonNull String consumerKey,
             @NonNull String consumerSecret,
             @NonNull String callback) {
        this.host = host;
        this.pathRequest = pathRequest;
        this.pathAuthorize = pathAuthorize;
        this.pathAccess = pathAccess;
        this.https = https;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.callback = callback;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState, R.layout.authorization_activity);

        setTitle(getAuthTitle());

        auth_1.setText(getAuthExplainShort());
        auth_2.setText(getAuthExplainLong());

        ImmutablePair<String, String> tempToken = getTempTokens();
        OAtoken = tempToken.left;
        OAtokenSecret = tempToken.right;

        startButton.setText(getAuthAuthorize());
        startButton.setEnabled(true);
        startButton.setOnClickListener(new StartListener());

        if (StringUtils.isBlank(OAtoken) && StringUtils.isBlank(OAtokenSecret)) {
            // start authorization process
            startButton.setText(getAuthStart());
        } else {
            // already have temporary tokens, continue from pin
            startButton.setText(getAuthAgain());
        }
    }

    @Override
    public void onNewIntent(final Intent intent) {
        setIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        final Uri uri = getIntent().getData();
        if (uri != null) {
            final String verifier = uri.getQueryParameter("oauth_verifier");
            if (StringUtils.isNotBlank(verifier)) {
                exchangeTokens(verifier);
            } else {
                // We can shortcut the whole verification process if we do not have a token at all.
                changeTokensHandler.sendEmptyMessage(NOT_AUTHENTICATED);
            }
        }
    }

    private void requestToken() {

        final Parameters params = new Parameters();
        params.put("oauth_callback", callback);
        final String method = "GET";
        OAuth.signOAuth(host, pathRequest, method, https, params, null, null, consumerKey, consumerSecret);
        final String line = Network.getResponseData(Network.getRequest(getUrlPrefix() + host + pathRequest, params));

        int status = 0;
        if (StringUtils.isNotBlank(line)) {
            assert line != null;
            final MatcherWrapper paramsMatcher1 = new MatcherWrapper(paramsPattern1, line);
            if (paramsMatcher1.find()) {
                OAtoken = paramsMatcher1.group(1);
            }
            final MatcherWrapper paramsMatcher2 = new MatcherWrapper(paramsPattern2, line);
            if (paramsMatcher2.find()) {
                OAtokenSecret = paramsMatcher2.group(1);
            }

            if (StringUtils.isNotBlank(OAtoken) && StringUtils.isNotBlank(OAtokenSecret)) {
                setTempTokens(OAtoken, OAtokenSecret);
                try {
                    final Parameters paramsBrowser = new Parameters();
                    paramsBrowser.put("oauth_token", OAtoken);
                    final String encodedParams = EntityUtils.toString(new UrlEncodedFormEntity(paramsBrowser));
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getUrlPrefix() + host + pathAuthorize + "?" + encodedParams)));
                    status = 1;
                } catch (ParseException e) {
                    Log.e("OAuthAuthorizationActivity.requestToken", e);
                } catch (IOException e) {
                    Log.e("OAuthAuthorizationActivity.requestToken", e);
                }
            }
        }

        requestTokenHandler.sendEmptyMessage(status);
    }

    private void changeToken(final String verifier) {

        int status = NOT_AUTHENTICATED;

        try {
            final Parameters params = new Parameters("oauth_verifier", verifier);

            final String method = "POST";
            OAuth.signOAuth(host, pathAccess, method, https, params, OAtoken, OAtokenSecret, consumerKey, consumerSecret);
            final String line = StringUtils.defaultString(Network.getResponseData(Network.postRequest(getUrlPrefix() + host + pathAccess, params)));

            OAtoken = "";
            OAtokenSecret = "";

            final MatcherWrapper paramsMatcher1 = new MatcherWrapper(paramsPattern1, line);
            if (paramsMatcher1.find()) {
                OAtoken = paramsMatcher1.group(1);
            }
            final MatcherWrapper paramsMatcher2 = new MatcherWrapper(paramsPattern2, line);
            if (paramsMatcher2.find() && paramsMatcher2.groupCount() > 0) {
                OAtokenSecret = paramsMatcher2.group(1);
            }

            if (StringUtils.isBlank(OAtoken) && StringUtils.isBlank(OAtokenSecret)) {
                OAtoken = "";
                OAtokenSecret = "";
                setTokens(null, null, false);
            } else {
                setTokens(OAtoken, OAtokenSecret, true);
                status = AUTHENTICATED;
            }
        } catch (Exception e) {
            Log.e("OAuthAuthorizationActivity.changeToken", e);
        }

        changeTokensHandler.sendEmptyMessage(status);
    }

    private String getUrlPrefix() {
        return https ? "https://" : "http://";
    }

    private class StartListener implements View.OnClickListener {

        @Override
        public void onClick(View arg0) {
            if (requestTokenDialog == null) {
                requestTokenDialog = new ProgressDialog(OAuthAuthorizationActivity.this);
                requestTokenDialog.setCancelable(false);
                requestTokenDialog.setMessage(getAuthDialogWait());
            }
            requestTokenDialog.show();
            startButton.setEnabled(false);
            startButton.setOnTouchListener(null);
            startButton.setOnClickListener(null);

            setTempTokens(null, null);
            (new Thread() {

                @Override
                public void run() {
                    requestToken();
                }
            }).start();
        }
    }

    private void exchangeTokens(final String verifier) {
        if (changeTokensDialog == null) {
            changeTokensDialog = new ProgressDialog(this);
            changeTokensDialog.setCancelable(false);
            changeTokensDialog.setMessage(getAuthDialogWait());
        }
        changeTokensDialog.show();

        (new Thread() {

            @Override
            public void run() {
                changeToken(verifier);
            }
        }).start();
    }

    protected abstract ImmutablePair<String, String> getTempTokens();

    protected abstract void setTempTokens(@Nullable String tokenPublic, @Nullable String tokenSecret);

    protected abstract void setTokens(@Nullable String tokenPublic, @Nullable String tokenSecret, boolean enable);

    // get resources from derived class

    protected abstract String getAuthTitle();

    protected String getAuthAgain() {
        return getString(R.string.auth_again);
    }

    protected String getErrAuthInitialize() {
        return getString(R.string.err_auth_initialize);
    }

    protected String getAuthStart() {
        return getString(R.string.auth_start);
    }

    protected abstract String getAuthDialogCompleted();

    protected String getErrAuthProcess() {
        return res.getString(R.string.err_auth_process);
    }

    protected String getAuthDialogWait() {
        return res.getString(R.string.auth_dialog_waiting, getAuthTitle());
    }

    protected String getAuthExplainShort() {
        return res.getString(R.string.auth_explain_short, getAuthTitle());
    }

    protected String getAuthExplainLong() {
        return res.getString(R.string.auth_explain_long, getAuthTitle());
    }

    protected String getAuthAuthorize() {
        return res.getString(R.string.auth_authorize, getAuthTitle());
    }

    protected String getAuthFinish() {
        return res.getString(R.string.auth_finish, getAuthTitle());
    }
}
