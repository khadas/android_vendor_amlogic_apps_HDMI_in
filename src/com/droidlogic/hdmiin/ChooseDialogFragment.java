/*
 * Copyright (c) 2014 Amlogic, Inc. All rights reserved.
 *
 * This source code is subject to the terms and conditions defined in the
 * file 'LICENSE' which is part of this source code package.
 *
 * Description:
 */

package com.droidlogic.hdmiin;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.RadioButton;
import android.util.Log;

public class ChooseDialogFragment extends DialogFragment {
    private static final String TAG = "ChooseDialog";
    private String mTitle;
    private View mView;
    private int mInputSource = -1;

    private RadioButton mSourceBtn3 = null;
    private RadioButton mSourceBtn2 = null;
    private RadioButton mSourceBtn1 = null;
    private RadioButton mSourceBtn0 = null;

    static ChooseDialogFragment newInstance(String title) {
        ChooseDialogFragment fragment = new ChooseDialogFragment();
        Bundle args = new Bundle();
        args.putString("title", title);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTitle = getArguments().getString("title");
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        buildDialog(builder, inflater);
        return builder.create();
    }

    public void buildDialog(AlertDialog.Builder builder, LayoutInflater inflater) {
        builder.setTitle(mTitle);
        mView = inflater.inflate(R.layout.settings, null);
        builder.setView(mView);

        mSourceBtn3 = (RadioButton)mView.findViewById(R.id.hdmi_in_3_btn);
        mSourceBtn2 = (RadioButton)mView.findViewById(R.id.hdmi_in_2_btn);
        mSourceBtn1 = (RadioButton)mView.findViewById(R.id.hdmi_in_1_btn);
        mSourceBtn0 = (RadioButton)mView.findViewById(R.id.hdmi_in_0_btn);
        mSourceBtn3.setChecked(true);
        mSourceBtn2.setChecked(false);
        mSourceBtn1.setChecked(false);
        mSourceBtn0.setChecked(false);
        mInputSource = 3;

        mSourceBtn3.setOnClickListener(new RadioButton.OnClickListener() {
            @Override
            public void onClick(View v) {
                mInputSource = 3;
            }
        });

        mSourceBtn2.setOnClickListener(new RadioButton.OnClickListener() {
            @Override
            public void onClick(View v) {
                mInputSource = 2;
            }
        });

        mSourceBtn1.setOnClickListener(new RadioButton.OnClickListener() {
            @Override
            public void onClick(View v) {
                mInputSource = 1;
            }
        });

        mSourceBtn0.setOnClickListener(new RadioButton.OnClickListener() {
            @Override
            public void onClick(View v) {
                mInputSource = 0;
            }
        });

        builder.setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                ChooseDialogFragment.this.getDialog().cancel();
                ((SettingsActivity)getActivity()).doConfirm(mInputSource);
            }
        });
        builder.setNegativeButton(R.string.discard, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                ChooseDialogFragment.this.getDialog().cancel();
                ((SettingsActivity)getActivity()).doDiscard();
            }
        });
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        ((SettingsActivity)getActivity()).doDismiss();
    }
}
