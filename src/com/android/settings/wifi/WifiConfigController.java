/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.wifi;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.net.DhcpInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.IpAssignment;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiInfo;
import android.security.Credentials;
import android.security.KeyStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.settings.R;
import java.net.UnknownHostException;

/**
 * The class for allowing UIs like {@link WifiDialog} and {@link WifiConfigPreference} to
 * share the logic for controlling buttons, text fields, etc.
 */
public class WifiConfigController implements TextWatcher,
        View.OnClickListener, AdapterView.OnItemSelectedListener {
    private static final String KEYSTORE_SPACE = "keystore://";

    private final WifiConfigUiBase mConfigUi;
    private final View mView;
    private final AccessPoint mAccessPoint;

    private boolean mEdit;

    private TextView mSsidView;

    // e.g. AccessPoint.SECURITY_NONE
    private int mAccessPointSecurity;
    private TextView mPasswordView;

    private Spinner mSecuritySpinner;
    private Spinner mEapMethodSpinner;
    private Spinner mEapCaCertSpinner;
    private Spinner mPhase2Spinner;
    private Spinner mEapUserCertSpinner;
    private TextView mEapIdentityView;
    private TextView mEapAnonymousView;

    private static final String STATIC_IP = "Static";
    private Spinner mIpSettingsSpinner;
    private TextView mIpAddressView;
    private TextView mGatewayView;
    private TextView mNetmaskView;
    private TextView mDns1View;
    private TextView mDns2View;

    static boolean requireKeyStore(WifiConfiguration config) {
        String values[] = {config.ca_cert.value(), config.client_cert.value(),
                config.private_key.value()};
        for (String value : values) {
            if (value != null && value.startsWith(KEYSTORE_SPACE)) {
                return true;
            }
        }
        return false;
    }

    public WifiConfigController(WifiConfigUiBase parent, View view, AccessPoint accessPoint,
            boolean edit, DialogInterface.OnClickListener listener) {
        mConfigUi = parent;

        mView = view;
        mAccessPoint = accessPoint;
        mAccessPointSecurity = (accessPoint == null) ? AccessPoint.SECURITY_NONE :
                accessPoint.security;
        mEdit = edit;

        final Context context = mConfigUi.getContext();
        final Resources resources = context.getResources();

        if (mAccessPoint == null) {
            mConfigUi.setTitle(R.string.wifi_add_network);
            mView.findViewById(R.id.type).setVisibility(View.VISIBLE);
            mSsidView = (TextView) mView.findViewById(R.id.ssid);
            mSsidView.addTextChangedListener(this);
            mSecuritySpinner = ((Spinner) mView.findViewById(R.id.security));
            mSecuritySpinner.setOnItemSelectedListener(this);
            mConfigUi.setSubmitButton(context.getString(R.string.wifi_save));
        } else {
            mConfigUi.setTitle(mAccessPoint.ssid);
            ViewGroup group = (ViewGroup) mView.findViewById(R.id.info);

            DetailedState state = mAccessPoint.getState();
            if (state != null) {
                addRow(group, R.string.wifi_status, Summary.get(mConfigUi.getContext(), state));
            }

            String[] type = resources.getStringArray(R.array.wifi_security);
            addRow(group, R.string.wifi_security, type[mAccessPoint.security]);

            int level = mAccessPoint.getLevel();
            if (level != -1) {
                String[] signal = resources.getStringArray(R.array.wifi_signal);
                addRow(group, R.string.wifi_signal, signal[level]);
            }

            WifiInfo info = mAccessPoint.getInfo();
            if (info != null) {
                addRow(group, R.string.wifi_speed, info.getLinkSpeed() + WifiInfo.LINK_SPEED_UNITS);
                // TODO: fix the ip address for IPv6.
                int address = info.getIpAddress();
                if (address != 0) {
                    addRow(group, R.string.wifi_ip_address, Formatter.formatIpAddress(address));
                }
            }

            if (mAccessPoint.networkId == -1 || mEdit) {
                showSecurityFields();
                showIpConfigFields();
            }

            if (mEdit) {
                mConfigUi.setSubmitButton(context.getString(R.string.wifi_save));
            } else {
                if (state == null && level != -1) {
                    mConfigUi.setSubmitButton(context.getString(R.string.wifi_connect));
                } else {
                    mView.findViewById(R.id.ipfields).setVisibility(View.GONE);
                }
                if (mAccessPoint.networkId != -1) {
                    mConfigUi.setForgetButton(context.getString(R.string.wifi_forget));
                }
            }
        }

        mIpSettingsSpinner = ((Spinner) mView.findViewById(R.id.ipsettings));
        if (mAccessPoint != null && mAccessPoint.networkId != -1) {
            WifiConfiguration config = mAccessPoint.getConfig();
            if (config.ipAssignment == IpAssignment.STATIC) {
                setSelection(mIpSettingsSpinner, STATIC_IP);
            }
        }
        mIpSettingsSpinner.setOnItemSelectedListener(this);

        mConfigUi.setCancelButton(context.getString(R.string.wifi_cancel));
        if (mConfigUi.getSubmitButton() != null) {
            enableSubmitIfAppropriate();
        }
    }

    private void addRow(ViewGroup group, int name, String value) {
        View row = mConfigUi.getLayoutInflater().inflate(R.layout.wifi_dialog_row, group, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        group.addView(row);
    }

    /* show submit button if the password is valid */
    private void enableSubmitIfAppropriate() {
        if ((mSsidView != null && mSsidView.length() == 0) ||
            ((mAccessPoint == null || mAccessPoint.networkId == -1) &&
            ((mAccessPointSecurity == AccessPoint.SECURITY_WEP && mPasswordView.length() == 0) ||
            (mAccessPointSecurity == AccessPoint.SECURITY_PSK && mPasswordView.length() < 8)))) {
            mConfigUi.getSubmitButton().setEnabled(false);
        } else {
            mConfigUi.getSubmitButton().setEnabled(true);
        }
    }

    /* package */ WifiConfiguration getConfig() {
        if (mAccessPoint != null && mAccessPoint.networkId != -1 && !mEdit) {
            return null;
        }

        WifiConfiguration config = new WifiConfiguration();

        if (mAccessPoint == null) {
            config.SSID = AccessPoint.convertToQuotedString(
                    mSsidView.getText().toString());
            // If the user adds a network manually, assume that it is hidden.
            config.hiddenSSID = true;
        } else if (mAccessPoint.networkId == -1) {
            config.SSID = AccessPoint.convertToQuotedString(
                    mAccessPoint.ssid);
        } else {
            config.networkId = mAccessPoint.networkId;
        }

        switch (mAccessPointSecurity) {
            case AccessPoint.SECURITY_NONE:
                config.allowedKeyManagement.set(KeyMgmt.NONE);
                break;

            case AccessPoint.SECURITY_WEP:
                config.allowedKeyManagement.set(KeyMgmt.NONE);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                config.allowedAuthAlgorithms.set(AuthAlgorithm.SHARED);
                if (mPasswordView.length() != 0) {
                    int length = mPasswordView.length();
                    String password = mPasswordView.getText().toString();
                    // WEP-40, WEP-104, and 256-bit WEP (WEP-232?)
                    if ((length == 10 || length == 26 || length == 58) &&
                            password.matches("[0-9A-Fa-f]*")) {
                        config.wepKeys[0] = password;
                    } else {
                        config.wepKeys[0] = '"' + password + '"';
                    }
                }
                break;

            case AccessPoint.SECURITY_PSK:
                config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
                if (mPasswordView.length() != 0) {
                    String password = mPasswordView.getText().toString();
                    if (password.matches("[0-9A-Fa-f]{64}")) {
                        config.preSharedKey = password;
                    } else {
                        config.preSharedKey = '"' + password + '"';
                    }
                }
                break;

            case AccessPoint.SECURITY_EAP:
                config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
                config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
                config.eap.setValue((String) mEapMethodSpinner.getSelectedItem());

                config.phase2.setValue((mPhase2Spinner.getSelectedItemPosition() == 0) ? "" :
                        "auth=" + mPhase2Spinner.getSelectedItem());
                config.ca_cert.setValue((mEapCaCertSpinner.getSelectedItemPosition() == 0) ? "" :
                        KEYSTORE_SPACE + Credentials.CA_CERTIFICATE +
                        (String) mEapCaCertSpinner.getSelectedItem());
                config.client_cert.setValue((mEapUserCertSpinner.getSelectedItemPosition() == 0) ?
                        "" : KEYSTORE_SPACE + Credentials.USER_CERTIFICATE +
                        (String) mEapUserCertSpinner.getSelectedItem());
                config.private_key.setValue((mEapUserCertSpinner.getSelectedItemPosition() == 0) ?
                        "" : KEYSTORE_SPACE + Credentials.USER_PRIVATE_KEY +
                        (String) mEapUserCertSpinner.getSelectedItem());
                config.identity.setValue((mEapIdentityView.length() == 0) ? "" :
                        mEapIdentityView.getText().toString());
                config.anonymous_identity.setValue((mEapAnonymousView.length() == 0) ? "" :
                        mEapAnonymousView.getText().toString());
                if (mPasswordView.length() != 0) {
                    config.password.setValue(mPasswordView.getText().toString());
                }
                break;

            default:
                    return null;
        }

        config.ipAssignment = mIpSettingsSpinner.getSelectedItem().toString().equals(STATIC_IP) ?
                IpAssignment.STATIC : IpAssignment.DHCP;

        if (config.ipAssignment == IpAssignment.STATIC) {
            //TODO: A better way to do this is to not dismiss the
            //dialog as long as one of the fields is invalid
            try {
                config.ipConfig.ipAddress = stringToIpAddr(mIpAddressView.getText().toString());
                config.ipConfig.gateway = stringToIpAddr(mGatewayView.getText().toString());
                config.ipConfig.netmask = stringToIpAddr(mNetmaskView.getText().toString());
                config.ipConfig.dns1 = stringToIpAddr(mDns1View.getText().toString());
                if (mDns2View.getText() != null && mDns2View.getText().length() > 0) {
                    config.ipConfig.dns2 = stringToIpAddr(mDns2View.getText().toString());
                }
            } catch (UnknownHostException e) {
                Toast.makeText(mConfigUi.getContext(), R.string.wifi_ip_settings_invalid_ip,
                        Toast.LENGTH_LONG).show();
                return null;
            }
        }

        return config;
    }

    private void showSecurityFields() {
        if (mAccessPointSecurity == AccessPoint.SECURITY_NONE) {
            mView.findViewById(R.id.fields).setVisibility(View.GONE);
            return;
        }
        mView.findViewById(R.id.fields).setVisibility(View.VISIBLE);

        if (mPasswordView == null) {
            mPasswordView = (TextView) mView.findViewById(R.id.password);
            mPasswordView.addTextChangedListener(this);
            ((CheckBox) mView.findViewById(R.id.show_password)).setOnClickListener(this);

            if (mAccessPoint != null && mAccessPoint.networkId != -1) {
                mPasswordView.setHint(R.string.wifi_unchanged);
            }
        }

        if (mAccessPointSecurity != AccessPoint.SECURITY_EAP) {
            mView.findViewById(R.id.eap).setVisibility(View.GONE);
            return;
        }
        mView.findViewById(R.id.eap).setVisibility(View.VISIBLE);

        if (mEapMethodSpinner == null) {
            mEapMethodSpinner = (Spinner) mView.findViewById(R.id.method);
            mPhase2Spinner = (Spinner) mView.findViewById(R.id.phase2);
            mEapCaCertSpinner = (Spinner) mView.findViewById(R.id.ca_cert);
            mEapUserCertSpinner = (Spinner) mView.findViewById(R.id.user_cert);
            mEapIdentityView = (TextView) mView.findViewById(R.id.identity);
            mEapAnonymousView = (TextView) mView.findViewById(R.id.anonymous);

            loadCertificates(mEapCaCertSpinner, Credentials.CA_CERTIFICATE);
            loadCertificates(mEapUserCertSpinner, Credentials.USER_PRIVATE_KEY);

            if (mAccessPoint != null && mAccessPoint.networkId != -1) {
                WifiConfiguration config = mAccessPoint.getConfig();
                setSelection(mEapMethodSpinner, config.eap.value());
                setSelection(mPhase2Spinner, config.phase2.value());
                setCertificate(mEapCaCertSpinner, Credentials.CA_CERTIFICATE,
                        config.ca_cert.value());
                setCertificate(mEapUserCertSpinner, Credentials.USER_PRIVATE_KEY,
                        config.private_key.value());
                mEapIdentityView.setText(config.identity.value());
                mEapAnonymousView.setText(config.anonymous_identity.value());
            }
        }
    }

    private void showIpConfigFields() {
        WifiConfiguration config = null;

        mView.findViewById(R.id.ipfields).setVisibility(View.VISIBLE);

        if (mIpSettingsSpinner == null) {
            mIpSettingsSpinner = (Spinner) mView.findViewById(R.id.ipsettings);
        }

        if (mAccessPoint != null && mAccessPoint.networkId != -1) {
            config = mAccessPoint.getConfig();
        }

        if (mIpSettingsSpinner.getSelectedItem().equals(STATIC_IP)) {
            mView.findViewById(R.id.staticip).setVisibility(View.VISIBLE);
            if (mIpAddressView == null) {
                mIpAddressView = (TextView) mView.findViewById(R.id.ipaddress);
                mGatewayView = (TextView) mView.findViewById(R.id.gateway);
                mNetmaskView = (TextView) mView.findViewById(R.id.netmask);
                mDns1View = (TextView) mView.findViewById(R.id.dns1);
                mDns2View = (TextView) mView.findViewById(R.id.dns2);
            }
            if (config != null) {
                DhcpInfo ipConfig = config.ipConfig;
                if (ipConfig != null && ipConfig.ipAddress != 0) {
                    mIpAddressView.setText(intToIpString(ipConfig.ipAddress));
                    mGatewayView.setText(intToIpString(ipConfig.gateway));
                    mNetmaskView.setText(intToIpString(ipConfig.netmask));
                    mDns1View.setText(intToIpString(ipConfig.dns1));
                    mDns2View.setText(intToIpString(ipConfig.dns2));
                }
            }
        } else {
            mView.findViewById(R.id.staticip).setVisibility(View.GONE);
        }
    }


    private void loadCertificates(Spinner spinner, String prefix) {
        final Context context = mConfigUi.getContext();
        final String unspecified = context.getString(R.string.wifi_unspecified);

        String[] certs = KeyStore.getInstance().saw(prefix);
        if (certs == null || certs.length == 0) {
            certs = new String[] {unspecified};
        } else {
            final String[] array = new String[certs.length + 1];
            array[0] = unspecified;
            System.arraycopy(certs, 0, array, 1, certs.length);
            certs = array;
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item, certs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setCertificate(Spinner spinner, String prefix, String cert) {
        prefix = KEYSTORE_SPACE + prefix;
        if (cert != null && cert.startsWith(prefix)) {
            setSelection(spinner, cert.substring(prefix.length()));
        }
    }

    private void setSelection(Spinner spinner, String value) {
        if (value != null) {
            ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
            for (int i = adapter.getCount() - 1; i >= 0; --i) {
                if (value.equals(adapter.getItem(i))) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        enableSubmitIfAppropriate();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void onClick(View view) {
        mPasswordView.setInputType(
                InputType.TYPE_CLASS_TEXT | (((CheckBox) view).isChecked() ?
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                InputType.TYPE_TEXT_VARIATION_PASSWORD));
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (view == mSecuritySpinner) {
            mAccessPointSecurity = position;
            showSecurityFields();
            enableSubmitIfAppropriate();
        } else {
            showIpConfigFields();
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    /* TODO: should go away when we move to IPv6 based config storage */
    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }

            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]) << 8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;

            return a | b | c | d;
        } catch (NumberFormatException e) {
            throw new UnknownHostException(addrString);
        }
    }

    private static String intToIpString(int i) {
        return (i & 0xFF) + "." + ((i >>  8 ) & 0xFF) + "." +((i >> 16 ) & 0xFF) + "." +
            ((i >> 24 ) & 0xFF);
    }

}