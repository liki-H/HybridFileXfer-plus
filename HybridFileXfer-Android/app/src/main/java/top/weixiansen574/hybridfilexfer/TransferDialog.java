package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import top.weixiansen574.hybridfilexfer.core.TransferGate;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public class TransferDialog {
    public final boolean isUpload;
    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    /** 取当前传输的门控（可能返回 null = 当前无传输） */
    private final Supplier<TransferGate> gateSupplier;
    private boolean paused;
    private AlertDialog dialog;


    public TransferDialog(Context context, boolean isUpload, List<String> channelNames) {
        this(context, isUpload, channelNames, null);
    }

    public TransferDialog(Context context, boolean isUpload, List<String> channelNames,
                          Supplier<TransferGate> gateSupplier) {
        this.context = context;
        this.gateSupplier = gateSupplier;
        holderMap = new HashMap<>();
        LayoutInflater inflater = LayoutInflater.from(context);
        dialogView = View.inflate(context, R.layout.dialog_transfer, null);
        LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout);
        for (String channelName : channelNames) {
            View itemView = inflater.inflate(R.layout.item_net_interface_status, linearLayout, false);
            //只保留上或下的其中一个速度指示器
            if (isUpload) {
                itemView.findViewById(R.id.download_speed).setVisibility(View.GONE);
            } else {
                itemView.findViewById(R.id.upload_speed).setVisibility(View.GONE);
            }
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(channelName)));
            holderMap.put(channelName, holder);
            linearLayout.addView(itemView);
        }
        this.isUpload = isUpload;
    }


    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(isUpload ? context.getString(R.string.uploading_speed_zero) :
                        context.getString(R.string.downloading_speed_zero))
                .setView(dialogView)
                .setPositiveButton(R.string.complete, null)
                .setCancelable(false);
        if (gateSupplier != null) {
            // 暂停/继续：只做本地门控，不拆 socket、不发协议消息
            builder.setNeutralButton(R.string.pause, null);
        }
        dialog = builder.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        if (gateSupplier != null) {
            Button neutralButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            neutralButton.setOnClickListener(v -> togglePause());
        }
    }

    /** 暂停/继续切换 */
    private void togglePause() {
        TransferGate gate = gateSupplier == null ? null : gateSupplier.get();
        if (gate == null) {
            // 当前没有进行中的传输（会话已结束）
            return;
        }
        Button neutralButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (gate.isPaused()) {
            gate.resume();
            paused = false;
            if (neutralButton != null) {
                neutralButton.setText(R.string.pause);
            }
        } else {
            gate.pause();
            paused = true;
            if (neutralButton != null) {
                neutralButton.setText(R.string.resume);
            }
        }
    }

    private void setNeutralBtnEnable(boolean enable) {
        if (dialog == null || gateSupplier == null) {
            return;
        }
        Button neutralButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (neutralButton != null) {
            neutralButton.setEnabled(enable);
        }
    }

    public void showEvent(String iName, String event) {
        Objects.requireNonNull(holderMap.get(iName)).transferEvent.setText(event);
    }

    public void showSpeeds(List<TrafficInfo> trafficInfoList) {
        long totalUploadSpeed = 0;
        long totalDownloadSpeed = 0;
        for (TrafficInfo trafficInfo : trafficInfoList) {
            Holder holder = holderMap.get(trafficInfo.iName);
            if (holder != null) {
                holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                totalUploadSpeed += trafficInfo.uploadTraffic;
                totalDownloadSpeed += trafficInfo.downloadTraffic;
            }
        }
        if (isUpload) {
            dialog.setTitle(context.getString(R.string.uploading_speed,
                    ((float) totalUploadSpeed) / 1024 / 1024));
        } else {
            dialog.setTitle(context.getString(R.string.downloading_speed,
                    ((float) totalDownloadSpeed) / 1024 / 1024));
        }
    }

    public void complete(boolean isUpload, long traffic, long time) {
        setCloseBtnEnable(true);
        dialog.setTitle((isUpload ? "▲" : "▼") + Utils.formatSpeed(traffic / time * 1000) +
                "·" + Utils.formatTime(time) + "·" + Utils.formatFileSize(traffic));
        for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
            Holder holder = entry.getValue();
            holder.downloadSpeed.setText(Utils.formatSpeed(0));
            holder.uploadSpeed.setText(Utils.formatSpeed(0));
        }
    }

    public void setTitle(String title) {
        dialog.setTitle(title);
    }

    public void setCloseBtnEnable(boolean enable) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enable);
        // 传输结束（成功/失败/中止）后门控已失效，暂停按钮不再有意义
        if (enable) {
            paused = false;
            setNeutralBtnEnable(false);
        }
    }

    public void setButton(String text, View.OnClickListener onClickListener) {
        Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setText(text);
        button.setOnClickListener(onClickListener);
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public AlertDialog getDialog() {
        return dialog;
    }

    private static class Holder {
        final View itemView;
        ImageView channelIcon;
        TextView channelName, uploadSpeed, downloadSpeed, transferEvent;

        public Holder(View itemView) {
            this.itemView = itemView;
            channelIcon = itemView.findViewById(R.id.channel_icon);
            channelName = itemView.findViewById(R.id.channel_name);
            uploadSpeed = itemView.findViewById(R.id.upload_speed);
            downloadSpeed = itemView.findViewById(R.id.download_speed);
            transferEvent = itemView.findViewById(R.id.txv_transfer_event);
        }
    }
}
