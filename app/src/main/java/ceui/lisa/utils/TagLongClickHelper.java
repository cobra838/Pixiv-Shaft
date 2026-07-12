package ceui.lisa.utils;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.SearchEntity;
import ceui.lisa.models.TagsBean;
import ceui.pixiv.ui.synonym.SynonymOperate;

public final class TagLongClickHelper {

    public interface PinToggleAction {
        void onPinToggle(String name, @Nullable String translated, boolean newPinned);
    }

    private TagLongClickHelper() {
    }

    public static void showTagActions(
            Context context,
            String name,
            @Nullable String translated,
            @Nullable PinToggleAction pinToggleAction
    ) {
        final boolean hasTranslation = !TextUtils.isEmpty(translated);
        final SearchEntity existing = PixivOperate.getSearchHistory(
                name,
                SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD
        );
        final boolean isPinned = existing != null && existing.isPinned();
        final boolean isMuted = AppDatabase.getAppDatabase(context).searchDao()
                .getTagMuteEntityByID(name.hashCode()) != null;

        QMUIDialog.MessageDialogBuilder builder = new QMUIDialog.MessageDialogBuilder(context)
                .setTitle(hasTranslation ? name + "  " + translated : name)
                .setSkinManager(QMUISkinManager.defaultInstance(context))
                .setActionContainerOrientation(LinearLayout.HORIZONTAL)
                .addAction(context.getString(isPinned ? R.string.string_443 : R.string.string_442),
                        new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                if (pinToggleAction != null) {
                                    pinToggleAction.onPinToggle(name, translated, !isPinned);
                                } else {
                                    PixivOperate.insertPinnedSearchHistory(
                                            name,
                                            SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD,
                                            !isPinned,
                                            null
                                    );
                                    Common.showToast(R.string.operate_success);
                                }
                                dialog.dismiss();
                            }
                        });

        builder.addAction(context.getString(R.string.string_120),
                new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        Common.copy(context, name);
                        dialog.dismiss();
                    }
                });

        if (hasTranslation) {
            builder.addAction(context.getString(R.string.v3_tag_menu_copy_translation),
                    new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            Common.copy(context, translated);
                            dialog.dismiss();
                        }
                    });
        }

        if (Shaft.sSettings.isSynonymDictEnabled()) {
            builder.addAction(context.getString(R.string.synonym_add_as_synonym),
                    new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            SynonymOperate.showAddAsSynonymDialog(context, name, translated);
                            dialog.dismiss();
                        }
                    });
        }

        builder.addAction(context.getString(isMuted
                        ? R.string.v3_tag_menu_unmute
                        : R.string.v3_tag_menu_mute),
                new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        TagsBean bean = new TagsBean();
                        bean.setName(name);
                        bean.setTranslated_name(translated);
                        if (isMuted) {
                            PixivOperate.unMuteTag(bean, false);
                            Common.showToast(R.string.string_383);
                        } else {
                            PixivOperate.muteTag(bean);
                            Common.showToast(R.string.string_382);
                        }
                        dialog.dismiss();
                    }
                });

        QMUIDialog dialog = builder.create();
        dialog.show();
        makeActionAreaHorizontallyScrollable(context, dialog);
        Common.enableQmuiDialogTextSelection(dialog);
    }

    private static void makeActionAreaHorizontallyScrollable(Context context, QMUIDialog dialog) {
        View operatorLayout = dialog.findViewById(com.qmuiteam.qmui.R.id.qmui_dialog_operator_layout_id);
        if (operatorLayout == null || operatorLayout.getParent() instanceof HorizontalScrollView) {
            return;
        }
        if (!(operatorLayout.getParent() instanceof ViewGroup)) {
            return;
        }

        ViewGroup parent = (ViewGroup) operatorLayout.getParent();
        int index = parent.indexOfChild(operatorLayout);
        ViewGroup.LayoutParams originalParams = operatorLayout.getLayoutParams();
        parent.removeView(operatorLayout);

        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setId(com.qmuiteam.qmui.R.id.qmui_dialog_operator_layout_id);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setFillViewport(false);

        operatorLayout.setId(View.NO_ID);
        operatorLayout.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(operatorLayout);
        parent.addView(scrollView, index, originalParams);
    }
}
