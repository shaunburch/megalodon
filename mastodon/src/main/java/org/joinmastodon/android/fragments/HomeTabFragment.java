package org.joinmastodon.android.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.announcements.GetAnnouncements;
import org.joinmastodon.android.api.requests.lists.GetLists;
import org.joinmastodon.android.api.requests.tags.GetFollowedHashtags;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;
import org.joinmastodon.android.model.Announcement;
import org.joinmastodon.android.model.Hashtag;
import org.joinmastodon.android.model.HeaderPaginationList;
import org.joinmastodon.android.model.ListTimeline;
import org.joinmastodon.android.model.TimelineDefinition;
import org.joinmastodon.android.ui.SimpleViewHolder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.updater.GithubSelfUpdater;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.fragments.OnBackPressedListener;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;

public class HomeTabFragment extends MastodonToolbarFragment implements ScrollableToTop, OnBackPressedListener {
	private static final int ANNOUNCEMENTS_RESULT = 654;

	private String accountID;
	private MenuItem announcements, announcementsAction, settings, settingsAction;
//	private ImageView toolbarLogo;
	private Button toolbarShowNewPostsBtn;
	private boolean newPostsBtnShown;
	private AnimatorSet currentNewPostsAnim;
	private ViewPager2 pager;
	private View switcher;
	private FrameLayout toolbarFrame;
	private ImageView timelineIcon;
	private ImageView collapsedChevron;
	private TextView timelineTitle;
	private PopupMenu switcherPopup;
	private final Map<Integer, ListTimeline> listItems = new HashMap<>();
	private final Map<Integer, Hashtag> hashtagsItems = new HashMap<>();
	private List<TimelineDefinition> timelineDefinitions;
	private int count;
	private Fragment[] fragments;
	private FrameLayout[] tabViews;
	private TimelineDefinition[] timelines;
	private final Map<Integer, TimelineDefinition> timelinesByMenuItem = new HashMap<>();
	private SubMenu hashtagsMenu, listsMenu;
	private Menu optionsMenu;
	private MenuInflater optionsMenuInflater;
	private boolean announcementsBadged, settingsBadged;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments().getString("account");
		timelineDefinitions = GlobalUserPreferences.pinnedTimelines.getOrDefault(accountID, TimelineDefinition.DEFAULT_TIMELINES);
		assert timelineDefinitions != null;
		if (timelineDefinitions.size() == 0) timelineDefinitions = List.of(TimelineDefinition.HOME_TIMELINE);
		count = timelineDefinitions.size();
		fragments = new Fragment[count];
		tabViews = new FrameLayout[count];
		timelines = new TimelineDefinition[count];
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		FrameLayout view = new FrameLayout(getContext());
		pager = new ViewPager2(getContext());
		toolbarFrame = (FrameLayout) LayoutInflater.from(getContext()).inflate(R.layout.home_toolbar, getToolbar(), false);

		if (fragments[0] == null) {
			Bundle args = new Bundle();
			args.putString("account", accountID);
			args.putBoolean("__is_tab", true);
			args.putBoolean("onlyPosts", true);

			for (int i = 0; i < timelineDefinitions.size(); i++) {
				TimelineDefinition tl = timelineDefinitions.get(i);
				fragments[i] = tl.getFragment();
				timelines[i] = tl;
			}

			FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
			for (int i = 0; i < count; i++) {
				fragments[i].setArguments(timelines[i].populateArguments(new Bundle(args)));
				FrameLayout tabView = new FrameLayout(getActivity());
				tabView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
				tabView.setVisibility(View.GONE);
				tabView.setId(i + 1);
				transaction.add(i + 1, fragments[i]);
				view.addView(tabView);
				tabViews[i] = tabView;
			}
			transaction.commit();
		}

		view.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		return view;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);

		timelineIcon = toolbarFrame.findViewById(R.id.timeline_icon);
		timelineTitle = toolbarFrame.findViewById(R.id.timeline_title);
		collapsedChevron = toolbarFrame.findViewById(R.id.collapsed_chevron);
		switcher = toolbarFrame.findViewById(R.id.switcher_btn);
		switcherPopup = new PopupMenu(getContext(), switcher);
		switcherPopup.setOnMenuItemClickListener(this::onSwitcherItemSelected);
		UiUtils.enablePopupMenuIcons(getContext(), switcherPopup);
		switcher.setOnClickListener(v->switcherPopup.show());
		switcher.setOnTouchListener(switcherPopup.getDragToOpenListener());
		updateSwitcherMenu();

		UiUtils.reduceSwipeSensitivity(pager);
		pager.setUserInputEnabled(!GlobalUserPreferences.disableSwipe);
		pager.setAdapter(new HomePagerAdapter());
		pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){
			@Override
			public void onPageSelected(int position){
				updateSwitcherIcon(position);
				if (!timelines[position].equals(TimelineDefinition.HOME_TIMELINE)) hideNewPostsButton();
				if (fragments[position] instanceof BaseRecyclerFragment<?> page){
					if(!page.loaded && !page.isDataLoading()) page.loadData();
				}
			}
		});

		if (!GlobalUserPreferences.reduceMotion) {
			pager.setPageTransformer((v, pos) -> {
				if (tabViews[pager.getCurrentItem()] != v) return;
				float scaleFactor = Math.max(0.85f, 1 - Math.abs(pos) * 0.06f);
				switcher.setScaleY(scaleFactor);
				switcher.setScaleX(scaleFactor);
				switcher.setAlpha(Math.max(0.65f, 1 - Math.abs(pos)));
			});
		}

		updateToolbarLogo();

		ViewTreeObserver vto = getToolbar().getViewTreeObserver();
		if (vto.isAlive()) {
			vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
				@Override
				public void onGlobalLayout() {
					Toolbar t = getToolbar();
					if (t == null) return;
					int toolbarWidth = t.getWidth();
					if (toolbarWidth == 0) return;

					int toolbarFrameWidth = toolbarFrame.getWidth();
					int padding = toolbarWidth - toolbarFrameWidth;
					FrameLayout parent = ((FrameLayout) toolbarShowNewPostsBtn.getParent());
					if (padding == parent.getPaddingStart()) return;

					// toolbar frame goes from screen edge to beginning of right-aligned option buttons.
					// centering button by applying the same space on the left
					parent.setPaddingRelative(padding, 0, 0, 0);
					toolbarShowNewPostsBtn.setMaxWidth(toolbarWidth - padding * 2);

					switcher.setPivotX(V.dp(28)); // padding + half of icon
					switcher.setPivotY(switcher.getHeight() / 2f);
				}
			});
		}

		if(GithubSelfUpdater.needSelfUpdating()){
			E.register(this);
			updateUpdateState(GithubSelfUpdater.getInstance().getState());
		}
	}

	private void addListsToOptionsMenu() {
		Context ctx = getContext();
		listsMenu.clear();
		listsMenu.getItem().setVisible(listItems.size() > 0);
		UiUtils.insetPopupMenuIcon(ctx, UiUtils.makeBackItem(listsMenu));
		listItems.forEach((id, list) -> {
			MenuItem item = listsMenu.add(Menu.NONE, id, Menu.NONE, list.title);
			item.setIcon(R.drawable.ic_fluent_people_list_24_regular);
			UiUtils.insetPopupMenuIcon(ctx, item);
		});
	}

	private void addHashtagsToOptionsMenu() {
		Context ctx = getContext();
		hashtagsMenu.clear();
		hashtagsMenu.getItem().setVisible(hashtagsItems.size() > 0);
		UiUtils.insetPopupMenuIcon(ctx, UiUtils.makeBackItem(hashtagsMenu));
		hashtagsItems.forEach((id, hashtag) -> {
			MenuItem item = hashtagsMenu.add(Menu.NONE, id, Menu.NONE, hashtag.name);
			item.setIcon(R.drawable.ic_fluent_number_symbol_24_regular);
			UiUtils.insetPopupMenuIcon(ctx, item);
		});
	}

	public void updateToolbarLogo(){
		Toolbar toolbar = getToolbar();
		ViewParent parentView = toolbarFrame.getParent();
		if (parentView == toolbar) return;
		if (parentView instanceof Toolbar parentToolbar) parentToolbar.removeView(toolbarFrame);
		toolbar.addView(toolbarFrame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		toolbar.setOnClickListener(v->scrollToTop());
		toolbar.setNavigationContentDescription(R.string.back);
		toolbar.setContentInsetsAbsolute(0, toolbar.getContentInsetRight());

		updateSwitcherIcon(pager.getCurrentItem());

//		toolbarLogo=new ImageView(getActivity());
//		toolbarLogo.setScaleType(ImageView.ScaleType.CENTER);
//		toolbarLogo.setImageResource(R.drawable.logo);
//		toolbarLogo.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), android.R.attr.textColorPrimary)));

		toolbarShowNewPostsBtn=toolbarFrame.findViewById(R.id.show_new_posts_btn);
		toolbarShowNewPostsBtn.setCompoundDrawableTintList(toolbarShowNewPostsBtn.getTextColors());
		if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N) UiUtils.fixCompoundDrawableTintOnAndroid6(toolbarShowNewPostsBtn);
		toolbarShowNewPostsBtn.setOnClickListener(this::onNewPostsBtnClick);

		if(newPostsBtnShown){
			toolbarShowNewPostsBtn.setVisibility(View.VISIBLE);
			collapsedChevron.setVisibility(View.VISIBLE);
			collapsedChevron.setAlpha(1f);
			timelineTitle.setVisibility(View.GONE);
			timelineTitle.setAlpha(0f);
		}else{
			toolbarShowNewPostsBtn.setVisibility(View.INVISIBLE);
			toolbarShowNewPostsBtn.setAlpha(0f);
			collapsedChevron.setVisibility(View.GONE);
			collapsedChevron.setAlpha(0f);
			toolbarShowNewPostsBtn.setScaleX(.8f);
			toolbarShowNewPostsBtn.setScaleY(.8f);
			timelineTitle.setVisibility(View.VISIBLE);
		}
	}

	private void createOptionsMenu() {
		optionsMenu.clear();
		optionsMenuInflater.inflate(R.menu.home, optionsMenu);
		announcements = optionsMenu.findItem(R.id.announcements);
		announcementsAction = optionsMenu.findItem(R.id.announcements_action);
		settings = optionsMenu.findItem(R.id.settings);
		settingsAction = optionsMenu.findItem(R.id.settings_action);
		hashtagsMenu = optionsMenu.findItem(R.id.hashtags).getSubMenu();
		listsMenu = optionsMenu.findItem(R.id.lists).getSubMenu();

		announcements.setVisible(!announcementsBadged);
		announcementsAction.setVisible(announcementsBadged);
		settings.setVisible(!settingsBadged);
		settingsAction.setVisible(settingsBadged);

		UiUtils.enableOptionsMenuIcons(getContext(), optionsMenu,
				R.id.overflow, R.id.announcements_action, R.id.settings_action);

		addListsToOptionsMenu();
		addHashtagsToOptionsMenu();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		this.optionsMenu = menu;
		this.optionsMenuInflater = inflater;
		createOptionsMenu();

		new GetLists().setCallback(new Callback<>() {
			@Override
			public void onSuccess(List<ListTimeline> lists) {
				updateList(lists, listItems);
				addListsToOptionsMenu();
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getContext());
			}
		}).exec(accountID);

		new GetFollowedHashtags().setCallback(new Callback<>() {
			@Override
			public void onSuccess(HeaderPaginationList<Hashtag> hashtags) {
				updateList(hashtags, hashtagsItems);
				addHashtagsToOptionsMenu();
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getContext());
			}
		}).exec(accountID);

		new GetAnnouncements(false).setCallback(new Callback<>() {
			@Override
			public void onSuccess(List<Announcement> result) {
				if (result.stream().anyMatch(a -> !a.read)) {
					announcementsBadged = true;
					announcements.setVisible(false);
					announcementsAction.setVisible(true);
				}
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getActivity());
			}
		}).exec(accountID);
	}

	private <T> void updateList(List<T> addItems, Map<Integer, T> items) {
		if (addItems.size() == 0) return;
		for (int i = 0; i < addItems.size(); i++) items.put(View.generateViewId(), addItems.get(i));
	}

	private void updateSwitcherMenu() {
		Menu switcherMenu = switcherPopup.getMenu();
		switcherMenu.clear();
		timelinesByMenuItem.clear();

		for (TimelineDefinition tl : timelines) {
			int menuItemId = View.generateViewId();
			timelinesByMenuItem.put(menuItemId, tl);
			MenuItem item = switcherMenu.add(0, menuItemId, 0, tl.getTitle(getContext()));
			item.setIcon(tl.getIcon().iconRes);
		}

		switcherMenu.add(0, R.id.menu_edit, Menu.NONE, R.string.sk_edit_timelines)
				.setIcon(R.drawable.ic_fluent_edit_24_regular);

		UiUtils.enablePopupMenuIcons(getContext(), switcherPopup);
	}

	private boolean onSwitcherItemSelected(MenuItem item) {
		int id = item.getItemId();

		Bundle args = new Bundle();
		args.putString("account", accountID);

		if (id == R.id.menu_back) {
			switcher.post(() -> switcherPopup.show());
			return true;
		} else if (id == R.id.menu_edit) {
			Nav.go(getActivity(), EditTimelinesFragment.class, args);
		} else {
			TimelineDefinition tl = timelinesByMenuItem.get(id);
			if (tl != null) {
				for (int i = 0; i < timelines.length; i++) {
					if (timelines[i] == tl) {
						navigateTo(i);
						return true;
					}
				}
			}
		}
		return false;
	}
	private void navigateTo(int i) {
		navigateTo(i, !GlobalUserPreferences.reduceMotion);
	}

	private void navigateTo(int i, boolean smooth) {
		pager.setCurrentItem(i, smooth);
		updateSwitcherIcon(i);
	}

	private void updateSwitcherIcon(int i) {
		timelineIcon.setImageResource(timelines[i].getIcon().iconRes);
		timelineTitle.setText(timelines[i].getTitle(getContext()));
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		int id = item.getItemId();
		ListTimeline list;
		Hashtag hashtag;

		if (item.getItemId() == R.id.menu_back) {
			createOptionsMenu();
			optionsMenu.performIdentifierAction(R.id.overflow, 0);
			return true;
		} else if (id == R.id.settings || id == R.id.settings_action) {
			Nav.go(getActivity(), SettingsFragment.class, args);
		} else if (id == R.id.announcements || id == R.id.announcements_action) {
			Nav.goForResult(getActivity(), AnnouncementsFragment.class, args, ANNOUNCEMENTS_RESULT, this);
		} else if ((list = listItems.get(id)) != null) {
			args.putString("listID", list.id);
			args.putString("listTitle", list.title);
			args.putInt("repliesPolicy", list.repliesPolicy.ordinal());
			Nav.go(getActivity(), ListTimelineFragment.class, args);
		} else if ((hashtag = hashtagsItems.get(id)) != null) {
			args.putString("hashtag", hashtag.name);
			args.putBoolean("following", hashtag.following);
			Nav.go(getActivity(), HashtagTimelineFragment.class, args);
		}
		return true;
	}

	@Override
	public void scrollToTop(){
		((ScrollableToTop) fragments[pager.getCurrentItem()]).scrollToTop();
	}

	public void hideNewPostsButton(){
		if(!newPostsBtnShown)
			return;
		newPostsBtnShown=false;
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		timelineTitle.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(timelineTitle, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_X, .8f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_Y, .8f),
				ObjectAnimator.ofFloat(collapsedChevron, View.ALPHA, 0f)
		);
		set.setDuration(GlobalUserPreferences.reduceMotion ? 0 : 300);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				toolbarShowNewPostsBtn.setVisibility(View.INVISIBLE);
				collapsedChevron.setVisibility(View.GONE);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	public void showNewPostsButton(){
		if(newPostsBtnShown || pager == null || !timelines[pager.getCurrentItem()].equals(TimelineDefinition.HOME_TIMELINE))
			return;
		newPostsBtnShown=true;
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		toolbarShowNewPostsBtn.setVisibility(View.VISIBLE);
		collapsedChevron.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(timelineTitle, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_X, .8f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_Y, .8f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(collapsedChevron, View.ALPHA, 1f)
		);
		set.setDuration(GlobalUserPreferences.reduceMotion ? 0 : 300);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				timelineTitle.setVisibility(View.GONE);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	public boolean isNewPostsBtnShown() {
		return newPostsBtnShown;
	}

	private void onNewPostsBtnClick(View view) {
		if(newPostsBtnShown){
			hideNewPostsButton();
			scrollToTop();
		}
	}

	@Override
	public void onFragmentResult(int reqCode, boolean success, Bundle result){
		if (reqCode == ANNOUNCEMENTS_RESULT && success) {
			announcementsBadged = false;
			announcements.setVisible(true);
			announcementsAction.setVisible(false);
		}
	}

	private void updateUpdateState(GithubSelfUpdater.UpdateState state){
		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING) {
			settingsBadged = true;
			settingsAction.setVisible(true);
			settings.setVisible(false);
		}
	}

	@Subscribe
	public void onSelfUpdateStateChanged(SelfUpdateStateChangedEvent ev){
		updateUpdateState(ev.state);
	}

	@Override
	public boolean onBackPressed(){
		if(pager.getCurrentItem() > 0){
			navigateTo(0);
			return true;
		}
		return false;
	}

	@Override
	public void onDestroyView(){
		super.onDestroyView();
		if(GithubSelfUpdater.needSelfUpdating()){
			E.unregister(this);
		}
	}

	@Override
	protected void onShown() {
		super.onShown();
		Object pinnedTimelines = GlobalUserPreferences.pinnedTimelines.get(accountID);
		if (pinnedTimelines != null && timelineDefinitions != pinnedTimelines) UiUtils.restartApp();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		if (savedInstanceState == null) return;
		navigateTo(savedInstanceState.getInt("selectedTab"), false);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("selectedTab", pager.getCurrentItem());
	}

	private class HomePagerAdapter extends RecyclerView.Adapter<SimpleViewHolder> {
		@NonNull
		@Override
		public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			FrameLayout tabView = tabViews[viewType % getItemCount()];
			((ViewGroup)tabView.getParent()).removeView(tabView);
			tabView.setVisibility(View.VISIBLE);
			return new SimpleViewHolder(tabView);
		}

		@Override
		public void onBindViewHolder(@NonNull SimpleViewHolder holder, int position){}

		@Override
		public int getItemCount(){
			return count;
		}

		@Override
		public int getItemViewType(int position){
			return position;
		}
	}
}
