package de.danoeh.antennapod.ui.screen.saved;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;

/**
 * "Saved" nav drawer destination: everything the user chose to keep, in two
 * tabs — favorite episodes and mid-episode bookmarks.
 */
public class SavedFragment extends Fragment {
    public static final String TAG = "SavedFragment";
    private static final String KEY_UP_ARROW = "up_arrow";

    public static final int POS_FAVORITES = 0;
    public static final int POS_BOOKMARKS = 1;
    private static final int TOTAL_COUNT = 2;

    private boolean displayUpArrow;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.pager_fragment, container, false);
        MaterialToolbar toolbar = root.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.saved_label);
        displayUpArrow = getParentFragmentManager().getBackStackEntryCount() != 0;
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW);
        }
        ((MainActivity) getActivity()).setupToolbarToggle(toolbar, displayUpArrow);

        ViewPager2 viewPager = root.findViewById(R.id.viewpager);
        viewPager.setAdapter(new SavedPagerAdapter(this));
        TabLayout tabLayout = root.findViewById(R.id.sliding_tabs);
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == POS_FAVORITES) {
                tab.setText(R.string.favorite_episodes_label);
            } else {
                tab.setText(R.string.bookmarks_label);
            }
        }).attach();
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow);
        super.onSaveInstanceState(outState);
    }

    private static class SavedPagerAdapter extends FragmentStateAdapter {
        SavedPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == POS_FAVORITES) {
                return new FavoritesTabFragment();
            }
            return new BookmarksTabFragment();
        }

        @Override
        public int getItemCount() {
            return TOTAL_COUNT;
        }
    }
}
