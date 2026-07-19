package ceui.lisa.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.TextView;

import com.mxn.soul.flowingdrawer_core.ElasticDrawer;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager.widget.ViewPager;
import ceui.lisa.R;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.databinding.FragmentNewSearchBinding;
import ceui.pixiv.ui.search.SearchIllustFeedFragment;
import ceui.pixiv.ui.search.SearchNovelFeedFragment;
import ceui.pixiv.ui.search.SearchRiskPolicy;
import ceui.pixiv.ui.search.SearchUserFeedFragment;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.utils.SearchTypeUtil;
import ceui.lisa.viewmodel.SearchModel;
import ceui.loxia.ObjectType;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.search.SearchHintViewModel;
import ceui.pixiv.ui.search.v3.SearchFilterV3BottomSheet;
import ceui.pixiv.ui.search.v3.SearchFilterV3LegacyBridge;
import io.reactivex.schedulers.Schedulers;

public class SearchActivity extends BaseActivity<FragmentNewSearchBinding> {

    private final Fragment[] allPages = new Fragment[]{null, null, null};
    private String keyWord = "";
    private SearchModel searchModel;
    private int index = 0;
    private int mPosition = 0;
    private boolean isPremium = false;
    private long mExitTime;
    private final java.util.List<String> committedTags = new java.util.ArrayList<>();
    private SearchHintViewModel hintViewModel;
    private int editingTagIndex = -1;
    private boolean lastImeVisible = false;
    private boolean suppressHintUpdate = false;

    @Override
    protected void initBundle(Bundle bundle) {
        keyWord = bundle.getString(Params.KEY_WORD);
        index = bundle.getInt(Params.INDEX);
    }

    /**
     * ViewModel 创建/播种放这里而不是 initBundle：BaseActivity 只在 intent 带 extras 时才调
     * initBundle，但 initView/initData 里的 TextWatcher、筛选菜单、翻页监听器无条件挂载。
     * 一旦 Activity 被无 extras 地重建（系统/崩溃重启重投裸 intent），searchModel 就还是 null，
     * 首个按键 afterTextChanged → pushKeywordFromCurrentUi 直接 NPE。initModel 无条件调用，
     * 保证这两个核心 ViewModel 永远先于任何监听器就绪；keyWord/index 缺省时走字段默认值("",0)。
     */
    @Override
    public void initModel() {
        // AES/GCM 提供器与词库只在搜索页用到：进页即在后台预热，避免首次键入时冷解密卡主线程。
        Schedulers.computation().scheduleDirect(SearchRiskPolicy::warmUp);
        searchModel = new ViewModelProvider(this).get(SearchModel.class);
        hintViewModel = new ViewModelProvider(this).get(SearchHintViewModel.class);
        searchModel.getKeyword().setValue(keyWord);
        searchModel.getIsNovel().setValue(index == 1);

        isPremium = SessionManager.INSTANCE.isPremium();
        searchModel.getIsPremium().setValue(isPremium);

        // 首搜写历史：无论从哪儿带关键字进来（输入框搜索/提示词/热标签/详情页标签/发现/深链…），
        // 都在这唯一入口收口一次。首搜走 ensureLoaded 不发 nowGo，所以和下面的重搜 observer 不重复。
        recordKeywordHistory(keyWord);
    }

    /**
     * 关键字搜索写历史的唯一收口：首搜（{@link #initModel()} 拿到的 keyWord）与重搜（nowGo）都走这里，
     * 由 {@link PixivOperate#insertSearchHistory} 按 id（keyword.hashCode()+type）去重——同词只留一条。
     * 原先写入寄生在 {@code SearchIllustRepo.initApi}，会被 trending_builtin 提前 return 跳过、
     * 且只有插画 tab 触发；上移到这里后所有入口、所有 tab、所有排序都稳定写一条。
     */
    private void recordKeywordHistory(String keyword) {
        if (keyword == null) return;
        final String trimmed = keyword.trim();
        if (trimmed.isEmpty()) return;
        // 写库甩到 IO 线程：insertSearchHistory 是主键读 + 单条插入，search_table 极小虽轻，
        // 政策判断也留在这里，首次进搜索页即使后台预热尚未完成也不阻塞主线程。
        // initModel / nowGo 都跑在主线程，统一挪开不碰主线程 Room（对齐本仓
        // insertIllustViewHistory 等既有做法）。fire-and-forget，去重靠 id REPLACE，乱序无碍；
        // 只捕获 String + 静态方法，不持有 Activity，无泄漏。
        Schedulers.io().scheduleDirect(() -> {
            // 被拦截的查询不持久化；结果页仍保留当前 chip 来解释为什么未显示。
            if (!SearchRiskPolicy.shouldWithhold(trimmed)) {
                PixivOperate.insertSearchHistory(trimmed, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD);
            }
        });
    }

    @Override
    protected int initLayout() {
        return R.layout.fragment_new_search;
    }

    @Override
    protected void initView() {
        final String[] TITLES = new String[]{
                getString(R.string.string_136),
                getString(R.string.string_138),
                getString(R.string.string_432)
        };
        // Seed committed chips from the incoming keyword (space-separated), clear
        // the input itself — the chip row represents the active query.
        applySearchInputModeUI();
        syncCommittedTagsFromText(keyWord);
        if (useChipInputMode()) {
            baseBind.searchTagsFlow.setShowRemoveIcon(true);
            refreshChipsUI();
            baseBind.searchTagsFlow.setOnTagClick(name -> {
                startEditingChip(name);
                return kotlin.Unit.INSTANCE;
            });
            baseBind.searchTagsFlow.setOnTagRemoveClick(name -> {
                removeCommittedTag(name);
                refreshChipsUI();
                pushKeywordFromCurrentUi();
                triggerSearchIfNotEmpty();
                return kotlin.Unit.INSTANCE;
            });
            baseBind.searchTagsFlow.setOnTagLongClick(name -> {
                showTagActionMenu(name);
                return kotlin.Unit.INSTANCE;
            });
        } else {
            baseBind.searchKeywordBox.setText(keyWord);
            baseBind.searchKeywordBox.setSelection(baseBind.searchKeywordBox.getText().length());
            baseBind.clearSearchKeyword.setVisibility(TextUtils.isEmpty(keyWord) ? View.INVISIBLE : View.VISIBLE);
        }
        // 三个 tab 均已迁 feeds（autoLoad=false 懒加载）。必须用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT，
        // 否则离屏 tab 也到 RESUMED → onResume ensureLoaded → 开屏就替用户把三个 tab 各搜一次（旧 legacy
        // 靠 setUserVisibleHint 懒加载只搜可见 tab）。改 behavior 1 后只有可见 tab 开搜，其余进 tab 才搜。
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (allPages[position] == null) {
                    if (position == 0) {
                        allPages[position] = SearchIllustFeedFragment.newInstance();
                    } else if(position == 1){
                        allPages[position] = SearchNovelFeedFragment.newInstance();
                    } else if(position == 2){
                        allPages[position] = SearchUserFeedFragment.newInstance();
                    }
                }

                return allPages[position];
            }

            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return TITLES[position];
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener(){
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) { }

            @Override
            public void onPageSelected(int position) {
                hintViewModel.hideHints();
                mPosition = position;
                // V3 filter 不再用抽屉，全程禁掉抽屉触摸；SearchModel.isNovel 保持同步给
                // V3 filter sheet 判当前 tab 类型（feeds 版搜索 fragment 已改看 searchType gate，不读 isNovel）。
                MutableLiveData<Boolean> isNovel = searchModel.getIsNovel();
                if (isNovel.getValue() != null) {
                    if ((position == 0) && isNovel.getValue()) {
                        isNovel.setValue(false);
                    } else if (position == 1 && !isNovel.getValue()) {
                        isNovel.setValue(true);
                    }
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
        baseBind.viewPager.setOffscreenPageLimit(2);
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        // drawer 触摸响应在 initData 末尾跟着 bridge 一并关掉——不在这里重复
        if (index != 0) {
            baseBind.viewPager.setCurrentItem(index);
        }
    }

    @Override
    protected void initData() {
        installSearchUiDismissHandlers();
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.toolbar.inflateMenu(R.menu.illust_filter);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_filter) {
                    Common.hideKeyboard(mActivity);
                    if (mPosition == 0 || mPosition == 1) {
                        // V3 filter sheet 替代老抽屉里的 FragmentFilter；状态由
                        // SearchFilterV3LegacyBridge 翻译回 SearchModel。
                        String objectType = (mPosition == 1) ? ObjectType.NOVEL : ObjectType.ILLUST;
                        SearchFilterV3BottomSheet
                                .newInstance(objectType, true)
                                .show(getSupportFragmentManager(), "SearchFilterV3LegacySheet");
                    } else {
                        Common.showToast(getString(R.string.string_435));
                    }
                    return true;
                }
                return false;
            }
        });
        if (useChipInputMode()) {
            EditText chipEditor = baseBind.searchTagsFlow.getEditor();
            chipEditor.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void afterTextChanged(Editable editable) {
                    String current = editable.toString();
                    if (current.length() > 0 && current.charAt(current.length() - 1) == ' ') {
                        String tag = current.substring(0, current.length() - 1).trim();
                        if (tag.isEmpty()) {
                            chipEditor.setText("");
                        } else {
                            commitTagFromInput(tag);
                        }
                        hintViewModel.hideHints();
                        return;
                    }
                    pushKeywordFromCurrentUi();

                    String typed = current.trim();
                    if (!typed.isEmpty() && !Common.isNumeric(typed)) {
                        hintViewModel.onTextChanged(typed);
                    } else {
                        hintViewModel.clearHints();
                    }
                }
            });
            chipEditor.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    return submitCurrentSearch(chipEditor.getText().toString().trim());
                }
            });
        } else {
            baseBind.searchKeywordBox.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {

                }

                @Override
                public void afterTextChanged(Editable editable) {
                    String current = editable.toString();
                    baseBind.clearSearchKeyword.setVisibility(current.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                    syncCommittedTagsFromText(current);
                    searchModel.getKeyword().setValue(current.trim());
                    if (suppressHintUpdate) {
                        suppressHintUpdate = false;
                        hintViewModel.hideHints();
                        return;
                    }

                    String lastToken = getEditingToken(current);
                    if (!TextUtils.isEmpty(lastToken) && !Common.isNumeric(lastToken)) {
                        hintViewModel.onTextChanged(lastToken);
                    } else {
                        hintViewModel.clearHints();
                    }
                }
            });
            baseBind.searchKeywordBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    return submitCurrentSearch(baseBind.searchKeywordBox.getText().toString().trim());
                }
            });
            baseBind.searchKeywordBox.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && baseBind.hintList.getAdapter() != null) {
                        animateHintList(true);
                    }
                }
            });
            baseBind.searchKeywordBox.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                            && baseBind.hintList.getAdapter() instanceof SearchHintAdapter) {
                        SearchHintAdapter adapter = (SearchHintAdapter) baseBind.hintList.getAdapter();
                        if (TextUtils.equals(adapter.getKeyword(),
                                getEditingToken(baseBind.searchKeywordBox.getText().toString()))) {
                            animateHintList(true);
                        }
                    }
                    return false;
                }
            });
            baseBind.clearSearchKeyword.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.searchKeywordBox.setText("");
                    hintViewModel.hideHints();
                }
            });
        }

        // Position hint list right below the toolbar (above tabs + content)
        baseBind.toolbar.post(() -> {
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) baseBind.hintList.getLayoutParams();
            lp.topMargin = baseBind.toolbar.getBottom();
            baseBind.hintList.setLayoutParams(lp);
        });
        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
        hintViewModel.getHints().observe(this, hints -> {
            if (hints == null || hints.isEmpty()) return;
            String keyword = hintViewModel.getCurrentKeyword().getValue();
            SearchHintAdapter adapter = new SearchHintAdapter(hints, mContext, keyword != null ? keyword : "");
            adapter.setOnItemClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String tag = hints.get(position).getTag();
                if (useChipInputMode()) {
                    addCommittedTag(tag);
                    baseBind.searchTagsFlow.getEditor().setText("");
                    pushKeywordFromCurrentUi();
                    triggerSearchIfNotEmpty();
                } else {
                    String updatedKeyword = replaceLastKeywordToken(tag, false);
                    suppressHintUpdate = true;
                    baseBind.searchKeywordBox.setText(updatedKeyword);
                    baseBind.searchKeywordBox.setSelection(updatedKeyword.length());
                    syncCommittedTagsFromText(updatedKeyword);
                    searchModel.getKeyword().setValue(updatedKeyword);
                    searchModel.getNowGo().setValue("search_now");
                }
                Common.hideKeyboard(mActivity);
            });
            adapter.setOnItemLongClickListener((v, position, viewType) -> {
                hintViewModel.hideHints();
                String tagName = hints.get(position).getTag();
                if (useChipInputMode()) {
                    baseBind.searchTagsFlow.getEditor().setText(tagName);
                    baseBind.searchTagsFlow.getEditor().setSelection(tagName.length());
                } else {
                    String updatedKeyword = replaceLastKeywordToken(tagName, true);
                    baseBind.searchKeywordBox.setText(updatedKeyword);
                    baseBind.searchKeywordBox.setSelection(updatedKeyword.length());
                }
            });
            baseBind.hintList.setAdapter(adapter);
        });
        hintViewModel.getHintsVisible().observe(this, visible -> {
            animateHintList(visible != null && visible);
        });

        // 重搜写历史：results 页每次真正发起搜索（软键盘回车 / 提交 chip / 点提示词 / 筛选应用）
        // 都会给 nowGo 一脚，这里统一收口写一条（去重）。首搜不发 nowGo（走 ensureLoaded），
        // 已在 initModel 写过，故两者不重复。读 keyword 而非 chip：nowGo 一定在 keyword 落定后才发。
        searchModel.getNowGo().observe(this, ignored ->
                recordKeywordHistory(searchModel.getKeyword().getValue()));

        // V3 filter sheet 替代老 FragmentFilter 抽屉。bridge 启动后会持续把
        // V3 SearchViewModel 的 illustFilter / novelFilter 翻译到 SearchModel，
        // 并在 sheet 触发搜索事件时 setNowGo("search_now") 让老 fragment 自动刷新。
        SearchFilterV3LegacyBridge.INSTANCE.install(this, searchModel);
        // 关掉抽屉的触摸响应——抽屉里没东西了，避免侧边盲区误触。
        baseBind.drawerlayout.setTouchMode(ElasticDrawer.TOUCH_MODE_NONE);

        // 搜索结果退出二次确认（issue #939，默认关闭）：只拦系统返回（手势/按键）——
        // 长滑之后误触退出就是从这条路来的；工具栏返回箭头是明确点击，不拦。
        // 交互对齐 MainActivity.exit()：2 秒内按两次返回才退出，第一次只 toast 提示。
        // 开关状态每次返回时现读，从设置页改完回来立即生效，无需重建 Activity。
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (Shaft.sSettings.isSearchExitConfirm()
                        && System.currentTimeMillis() - mExitTime > 2000) {
                    Common.showToast(getString(R.string.double_click_finish));
                    mExitTime = System.currentTimeMillis();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    /**
     * Commit the typed text as a new chip (dedupe, clear input, sync keyword).
     * Space-triggered commits do NOT auto-search - Enter is still the "go" key.
     */
    private void commitTagFromInput(String tag) {
        addCommittedTag(tag);
        baseBind.searchTagsFlow.getEditor().setText("");
        pushKeywordFromCurrentUi();
    }

    private void refreshChipsUI() {
        baseBind.searchTagsFlow.setTagNames(new java.util.ArrayList<>(committedTags));
    }

    private String joinedChips() {
        return android.text.TextUtils.join(" ", committedTags);
    }

    private void pushKeywordFromCurrentUi() {
        if (!useChipInputMode()) {
            searchModel.getKeyword().setValue(baseBind.searchKeywordBox.getText().toString().trim());
            return;
        }
        String typed = baseBind.searchTagsFlow.getEditor().getText().toString().trim();
        java.util.ArrayList<String> previewTags = new java.util.ArrayList<>(committedTags);
        if (editingTagIndex >= 0 && editingTagIndex < previewTags.size()) {
            if (!TextUtils.isEmpty(typed)) {
                previewTags.set(editingTagIndex, typed);
            }
        } else if (!TextUtils.isEmpty(typed)) {
            previewTags.add(typed);
        }
        String combined = TextUtils.join(" ", previewTags);
        searchModel.getKeyword().setValue(combined);
    }

    private void triggerSearchIfNotEmpty() {
        if (useChipInputMode()) {
            if (!committedTags.isEmpty()) {
                searchModel.getNowGo().setValue("search_now");
            }
        } else if (!TextUtils.isEmpty(baseBind.searchKeywordBox.getText().toString().trim())) {
            searchModel.getNowGo().setValue("search_now");
        }
    }

    private boolean useChipInputMode() {
        return Shaft.sSettings.getSearchTagInputStyle() == 1;
    }

    private void applySearchInputModeUI() {
        int chipVisibility = useChipInputMode() ? View.VISIBLE : View.GONE;
        int textVisibility = useChipInputMode() ? View.GONE : View.VISIBLE;
        baseBind.searchTagsScroll.setVisibility(chipVisibility);
        baseBind.searchKeywordBoxContainer.setVisibility(textVisibility);
    }

    private void installSearchUiDismissHandlers() {
        baseBind.topParent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissTransientSearchUi();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dismissTransientSearchUi()) {
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.topParent, (v, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (lastImeVisible && !imeVisible) {
                dismissTransientSearchUi();
            }
            lastImeVisible = imeVisible;
            return insets;
        });
    }

    private boolean dismissTransientSearchUi() {
        boolean hadHints = baseBind.hintList.getVisibility() == View.VISIBLE;
        EditText input = getActiveSearchInput();
        boolean hadFocus = input != null && input.isFocused();
        boolean canceledChipEditing = false;
        if (useChipInputMode() && editingTagIndex >= 0) {
            EditText chipEditor = baseBind.searchTagsFlow.getEditor();
            if (chipEditor != null) {
                chipEditor.setText("");
            }
            editingTagIndex = -1;
            canceledChipEditing = true;
            pushKeywordFromCurrentUi();
        }
        if (hadHints) {
            hintViewModel.hideHints();
        }
        if (hadFocus) {
            input.clearFocus();
            baseBind.topParent.requestFocus();
        }
        if (hadHints || hadFocus || canceledChipEditing) {
            Common.hideKeyboard(mActivity);
        }
        return hadHints || hadFocus || canceledChipEditing;
    }

    @Override
    protected boolean onGlobalSwipeBackRequested() {
        return dismissTransientSearchUi();
    }

    private EditText getActiveSearchInput() {
        return useChipInputMode() ? baseBind.searchTagsFlow.getEditor() : baseBind.searchKeywordBox;
    }

    private void addCommittedTag(String tag) {
        if (TextUtils.isEmpty(tag)) {
            editingTagIndex = -1;
            return;
        }
        int existingIndex = committedTags.indexOf(tag);
        if (editingTagIndex >= 0 && editingTagIndex < committedTags.size()) {
            int targetIndex = editingTagIndex;
            if (existingIndex >= 0 && existingIndex != targetIndex) {
                committedTags.remove(existingIndex);
                if (existingIndex < targetIndex) {
                    targetIndex--;
                }
            }
            committedTags.set(targetIndex, tag);
        } else if (existingIndex < 0) {
            committedTags.add(tag);
        }
        editingTagIndex = -1;
        refreshChipsUI();
    }

    private void removeCommittedTag(String name) {
        int removedIndex = committedTags.indexOf(name);
        if (removedIndex < 0) {
            return;
        }
        committedTags.remove(removedIndex);
        if (editingTagIndex > removedIndex) {
            editingTagIndex--;
        } else if (editingTagIndex == removedIndex) {
            editingTagIndex = -1;
        }
    }

    private void startEditingChip(String name) {
        editingTagIndex = committedTags.indexOf(name);
        if (editingTagIndex < 0) {
            editingTagIndex = committedTags.size();
        }
        EditText ed = baseBind.searchTagsFlow.getEditor();
        if (ed != null) {
            ed.setText(name);
            ed.setSelection(name.length());
            ed.requestFocus();
            InputMethodManager imm = (InputMethodManager) mContext
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(ed, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        pushKeywordFromCurrentUi();
    }

    private void syncCommittedTagsFromText(String text) {
        committedTags.clear();
        committedTags.addAll(parseKeywordTokens(text));
    }

    private java.util.List<String> parseKeywordTokens(String text) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return result;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        for (String part : trimmed.split("\\s+")) {
            if (!TextUtils.isEmpty(part)) {
                result.add(part);
            }
        }
        return result;
    }

    private String getEditingToken(String current) {
        java.util.List<String> parts = parseKeywordTokens(current);
        if (parts.isEmpty()) {
            return "";
        }
        return current.endsWith(" ") ? "" : parts.get(parts.size() - 1);
    }

    private String replaceLastKeywordToken(String replacement, boolean appendTrailingSpace) {
        java.util.List<String> parts = parseKeywordTokens(baseBind.searchKeywordBox.getText().toString());
        boolean appendAsNew = baseBind.searchKeywordBox.getText().toString().endsWith(" ") || parts.isEmpty();
        if (appendAsNew) {
            parts.add(replacement);
        } else {
            parts.set(parts.size() - 1, replacement);
        }
        String updated = TextUtils.join(" ", parts);
        return appendTrailingSpace ? updated + " " : updated;
    }

    private boolean submitCurrentSearch(String trimmedKeyword) {
        if (TextUtils.isEmpty(trimmedKeyword)) {
            if (useChipInputMode() && !committedTags.isEmpty()) {
                searchModel.getKeyword().setValue(joinedChips());
                searchModel.getNowGo().setValue("search_now");
                Common.hideKeyboard(mActivity);
                hintViewModel.hideHints();
                return true;
            }
            if (!TextUtils.isEmpty(searchModel.getStarSize().getValue())) {
                pushKeywordFromCurrentUi();
                searchModel.getNowGo().setValue("search_now");
                Common.hideKeyboard(mActivity);
                hintViewModel.hideHints();
                return true;
            }
            Common.showToast(getString(R.string.string_139));
            return false;
        }

        String policyQuery = trimmedKeyword;
        if (useChipInputMode()) {
            String existingKeyword = joinedChips();
            if (!TextUtils.isEmpty(existingKeyword)) {
                policyQuery = existingKeyword + " " + trimmedKeyword;
            }
        }
        if (SearchRiskPolicy.shouldWithhold(policyQuery)) {
            if (useChipInputMode()) {
                addCommittedTag(trimmedKeyword);
                baseBind.searchTagsFlow.getEditor().setText("");
                searchModel.getKeyword().setValue(joinedChips());
            } else {
                syncCommittedTagsFromText(trimmedKeyword);
                searchModel.getKeyword().setValue(trimmedKeyword);
            }
            searchModel.getNowGo().setValue("search_now");
            Common.hideKeyboard(mActivity);
            hintViewModel.hideHints();
            return true;
        }

        if (URLUtil.isValidUrl(trimmedKeyword)) {
            try {
                PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_URL);
                Intent intent = new Intent(mContext, OutWakeActivity.class);
                intent.setData(Uri.parse(trimmedKeyword));
                startActivity(intent);
                mActivity.finish();
            } catch (Exception e) {
                Common.showToast(e.toString());
                e.printStackTrace();
            }
            hintViewModel.hideHints();
            return true;
        }

        if (Common.isNumeric(trimmedKeyword)) {
            QMUITipDialog tipDialog = new QMUITipDialog.Builder(mContext)
                    .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
                    .setTipWord(getString(R.string.string_429))
                    .create();
            tipDialog.show();
            PixivOperate.getIllustByID(tryParseId(trimmedKeyword), mContext, new Callback<Void>() {
                @Override
                public void doSomething(Void t) {
                    PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID);
                    tipDialog.dismiss();
                    mActivity.finish();
                }
            }, new Callback<Void>() {
                @Override
                public void doSomething(Void t) {
                    tipDialog.dismiss();
                    PixivOperate.insertSearchHistory(trimmedKeyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID);
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, Common.safeUserId(trimmedKeyword));
                    startActivity(intent);
                    mActivity.finish();
                }
            });
            hintViewModel.hideHints();
            return true;
        }

        if (useChipInputMode()) {
            addCommittedTag(trimmedKeyword);
            baseBind.searchTagsFlow.getEditor().setText("");
            searchModel.getKeyword().setValue(joinedChips());
        } else {
            syncCommittedTagsFromText(trimmedKeyword);
            searchModel.getKeyword().setValue(trimmedKeyword);
        }
        searchModel.getNowGo().setValue("search_now");
        Common.hideKeyboard(mActivity);
        hintViewModel.hideHints();
        return true;
    }

    private void animateHintList(boolean show) {
        if (show) {
            if (baseBind.hintList.getVisibility() == View.VISIBLE) return;
            baseBind.hintList.setAlpha(0f);
            baseBind.hintList.setTranslationY(-24f);
            baseBind.hintList.setVisibility(View.VISIBLE);
            baseBind.hintList.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            if (baseBind.hintList.getVisibility() != View.VISIBLE) return;
            baseBind.hintList.animate()
                    .alpha(0f)
                    .translationY(-16f)
                    .setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        baseBind.hintList.setVisibility(View.GONE);
                        baseBind.hintList.setTranslationY(0f);
                    })
                    .start();
        }
    }

    /**
     * 长按 chip 弹出的居中菜单：复制文本 / 删除 / 编辑。
     * 编辑＝把 chip 还原回输入框、移除该 chip、聚焦输入框唤起键盘，让用户改完再回车提交。
     */
    private void showTagActionMenu(String name) {
        String[] items = new String[]{
                getString(R.string.tag_action_copy),
                getString(R.string.tag_action_delete),
                getString(R.string.tag_action_edit)
        };
        new QMUIDialog.MenuDialogBuilder(mContext)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addItems(items, (dialog, which) -> {
                    if (which == 0) {
                        Common.copy(mContext, name);
                    } else if (which == 1) {
                        removeCommittedTag(name);
                        refreshChipsUI();
                        pushKeywordFromCurrentUi();
                        triggerSearchIfNotEmpty();
                    } else if (which == 2) {
                        startEditingChip(name);
                    }
                    dialog.dismiss();
                })
                .show();
    }
}
