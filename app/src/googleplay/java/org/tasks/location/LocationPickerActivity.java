package org.tasks.location;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.todoroo.andlib.utility.AndroidUtilities.atLeastLollipop;
import static com.todoroo.andlib.utility.AndroidUtilities.hideKeyboard;
import static org.tasks.PermissionUtil.verifyPermissions;
import static org.tasks.data.Place.newPlace;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnActionExpandListener;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.Toolbar.OnMenuItemClickListener;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.ContentLoadingProgressBar;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.AppBarLayout.Behavior;
import com.google.common.base.Strings;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.tasks.Event;
import org.tasks.R;
import org.tasks.billing.Inventory;
import org.tasks.data.LocationDao;
import org.tasks.data.PlaceUsage;
import org.tasks.dialogs.DialogBuilder;
import org.tasks.gtasks.PlayServices;
import org.tasks.injection.ActivityComponent;
import org.tasks.injection.ForApplication;
import org.tasks.injection.InjectingAppCompatActivity;
import org.tasks.location.LocationPickerAdapter.OnLocationPicked;
import org.tasks.location.LocationSearchAdapter.OnPredictionPicked;
import org.tasks.location.MapFragment.MapFragmentCallback;
import org.tasks.preferences.ActivityPermissionRequestor;
import org.tasks.preferences.PermissionChecker;
import org.tasks.preferences.PermissionRequestor;
import org.tasks.themes.Theme;
import org.tasks.themes.ThemeColor;
import org.tasks.ui.MenuColorizer;
import org.tasks.ui.Toaster;
import timber.log.Timber;

public class LocationPickerActivity extends InjectingAppCompatActivity
    implements OnMenuItemClickListener,
        MapFragmentCallback,
        OnLocationPicked,
        OnQueryTextListener,
        OnPredictionPicked,
        OnActionExpandListener {

  private static final String EXTRA_MAP_POSITION = "extra_map_position";
  private static final String EXTRA_APPBAR_OFFSET = "extra_appbar_offset";
  private static final Pattern pattern = Pattern.compile("(\\d+):(\\d+):(\\d+\\.\\d+)");
  private static final int SEARCH_DEBOUNCE_TIMEOUT = 300;

  @BindView(R.id.toolbar)
  Toolbar toolbar;

  @BindView(R.id.app_bar_layout)
  AppBarLayout appBarLayout;

  @BindView(R.id.coordinator)
  CoordinatorLayout coordinatorLayout;

  @BindView(R.id.search)
  View searchView;

  @BindView(R.id.loading_indicator)
  ContentLoadingProgressBar loadingIndicator;

  @BindView(R.id.choose_recent_location)
  View chooseRecentLocation;

  @BindView(R.id.recent_locations)
  RecyclerView recyclerView;

  @Inject @ForApplication Context context;
  @Inject Theme theme;
  @Inject Toaster toaster;
  @Inject Inventory inventory;
  @Inject PlayServices playServices;
  @Inject LocationDao locationDao;
  @Inject PlaceSearchProvider searchProvider;
  @Inject PermissionChecker permissionChecker;
  @Inject ActivityPermissionRequestor permissionRequestor;
  @Inject DialogBuilder dialogBuilder;
  @Inject MapFragment map;

  private FusedLocationProviderClient fusedLocationProviderClient;
  private CompositeDisposable disposables;
  private MapPosition mapPosition;
  private LocationPickerAdapter recentsAdapter = new LocationPickerAdapter(this);
  private LocationSearchAdapter searchAdapter = new LocationSearchAdapter(this);
  private List<PlaceUsage> places = Collections.emptyList();
  private int offset;
  private MenuItem search;
  private PublishSubject<String> searchSubject = PublishSubject.create();
  private PlaceSearchViewModel viewModel;

  private static String formatCoordinates(org.tasks.data.Place place) {
    return String.format(
        "%s %s",
        formatCoordinate(place.getLatitude(), true), formatCoordinate(place.getLongitude(), false));
  }

  private static String formatCoordinate(double coordinates, boolean latitude) {
    String output = Location.convert(Math.abs(coordinates), Location.FORMAT_SECONDS);
    Matcher matcher = pattern.matcher(output);
    if (matcher.matches()) {
      return String.format(
          "%s°%s'%s\"%s",
          matcher.group(1),
          matcher.group(2),
          matcher.group(3),
          latitude ? (coordinates > 0 ? "N" : "S") : (coordinates > 0 ? "E" : "W"));
    } else {
      return Double.toString(coordinates);
    }
  }

  private boolean canSearch() {
    return atLeastLollipop() || inventory.hasPro();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    theme.applyTheme(this);
    setContentView(R.layout.activity_location_picker);
    ButterKnife.bind(this);

    viewModel = ViewModelProviders.of(this).get(PlaceSearchViewModel.class);
    viewModel.setSearchProvider(searchProvider);

    Configuration configuration = getResources().getConfiguration();
    if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        && configuration.smallestScreenWidthDp < 480) {
      searchView.setVisibility(View.GONE);
    }

    if (savedInstanceState != null) {
      mapPosition = savedInstanceState.getParcelable(EXTRA_MAP_POSITION);
      offset = savedInstanceState.getInt(EXTRA_APPBAR_OFFSET);
      viewModel.restoreState(savedInstanceState);
    }

    toolbar.setNavigationIcon(R.drawable.ic_outline_arrow_back_24px);
    toolbar.setNavigationOnClickListener(v -> collapseToolbar());
    if (canSearch()) {
      toolbar.inflateMenu(R.menu.menu_location_picker);
      Menu menu = toolbar.getMenu();
      search = menu.findItem(R.id.menu_search);
      search.setOnActionExpandListener(this);
      ((SearchView) search.getActionView()).setOnQueryTextListener(this);
      toolbar.setOnMenuItemClickListener(this);
    } else {
      searchView.setVisibility(View.GONE);
    }

    MenuColorizer.colorToolbar(this, toolbar);
    ThemeColor themeColor = theme.getThemeColor();
    themeColor.applyToStatusBarIcons(this);
    themeColor.applyToNavigationBar(this);

    map.init(getSupportFragmentManager(), this, theme.getThemeBase().isDarkTheme(this));

    fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

    CoordinatorLayout.LayoutParams params =
        (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
    Behavior behavior = new Behavior();
    behavior.setDragCallback(
        new AppBarLayout.Behavior.DragCallback() {
          @Override
          public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
            return false;
          }
        });
    params.setBehavior(behavior);

    appBarLayout.addOnOffsetChangedListener(
        (appBarLayout, offset) -> {
          if (offset == 0 && this.offset != 0) {
            closeSearch();
            hideKeyboard(this);
          }
          this.offset = offset;
          toolbar.setAlpha(Math.abs(offset / (float) appBarLayout.getTotalScrollRange()));
        });

    coordinatorLayout.addOnLayoutChangeListener(
        new OnLayoutChangeListener() {
          @Override
          public void onLayoutChange(
              View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            coordinatorLayout.removeOnLayoutChangeListener(this);
            updateAppbarLayout();
          }
        });

    if (offset != 0) {
      appBarLayout.post(() -> expandToolbar(false));
    }

    recentsAdapter.setHasStableIds(true);
    recyclerView.setLayoutManager(new LinearLayoutManager(this));
    recyclerView.setAdapter(search.isActionViewExpanded() ? searchAdapter : recentsAdapter);
  }

  @Override
  public void onMapReady(MapFragment mapFragment) {
    map = mapFragment;
    updateMarkers();
    if (permissionChecker.canAccessLocation()) {
      mapFragment.showMyLocation();
    }
    if (mapPosition != null) {
      map.movePosition(mapPosition, false);
    } else if (permissionRequestor.requestFineLocation()) {
      moveToCurrentLocation(false);
    }
  }

  @Override
  public void onBackPressed() {
    if (closeSearch()) {
      return;
    }

    if (offset != 0) {
      collapseToolbar();
      return;
    }

    super.onBackPressed();
  }

  private boolean closeSearch() {
    return search.isActionViewExpanded() && search.collapseActionView();
  }

  @Override
  public void onPlaceSelected(org.tasks.data.Place place) {
    returnPlace(place);
  }

  @OnClick(R.id.current_location)
  void onClick() {
    if (permissionRequestor.requestFineLocation()) {
      moveToCurrentLocation(true);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode == PermissionRequestor.REQUEST_LOCATION) {
      if (verifyPermissions(grantResults)) {
        map.showMyLocation();
        moveToCurrentLocation(true);
      } else {
        dialogBuilder
            .newMessageDialog(R.string.location_permission_required_location)
            .setTitle(R.string.missing_permissions)
            .setPositiveButton(android.R.string.ok, null)
            .show();
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
  }

  @OnClick(R.id.select_this_location)
  void selectLocation() {
    loadingIndicator.setVisibility(View.VISIBLE);

    MapPosition mapPosition = map.getMapPosition();
    disposables.add(
        Single.fromCallable(
                () -> {
                  Geocoder geocoder = new Geocoder(this);
                  return geocoder.getFromLocation(
                      mapPosition.getLatitude(), mapPosition.getLongitude(), 1);
                })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError(e -> toaster.longToast(e.getMessage()))
            .doFinally(() -> loadingIndicator.setVisibility(View.GONE))
            .subscribe(
                addresses -> {
                  org.tasks.data.Place place = newPlace();
                  if (addresses.isEmpty()) {
                    place.setLatitude(mapPosition.getLatitude());
                    place.setLongitude(mapPosition.getLongitude());
                  } else {
                    Address address = addresses.get(0);
                    place.setLatitude(address.getLatitude());
                    place.setLongitude(address.getLongitude());
                    StringBuilder stringBuilder = new StringBuilder();
                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                      stringBuilder.append(address.getAddressLine(i)).append("\n");
                    }
                    place.setPhone(address.getPhone());
                    place.setAddress(stringBuilder.toString().trim());
                    String url = address.getUrl();
                    if (!Strings.isNullOrEmpty(url)) {
                      place.setUrl(url);
                    }
                  }
                  place.setName(formatCoordinates(place));
                  returnPlace(place);
                }));
  }

  @OnClick(R.id.search)
  void searchPlace() {
    mapPosition = map.getMapPosition();
    expandToolbar(true);
    search.expandActionView();
  }

  @SuppressLint("MissingPermission")
  private void moveToCurrentLocation(boolean animate) {
    fusedLocationProviderClient
        .getLastLocation()
        .addOnSuccessListener(
            location -> {
              if (location != null) {
                map.movePosition(
                    new MapPosition(location.getLatitude(), location.getLongitude(), 15f), animate);
              }
            });
  }

  private void returnPlace(org.tasks.data.Place place) {
    if (place == null) {
      Timber.e("Place is null");
      return;
    }
    if (place.getId() <= 0) {
      org.tasks.data.Place existing =
          locationDao.findPlace(place.getLatitude(), place.getLongitude());
      if (existing == null) {
        long placeId = locationDao.insert(place);
        place.setId(placeId);
      } else {
        existing.apply(place);
        locationDao.update(existing);
        place = existing;
      }
    }
    hideKeyboard(this);
    setResult(RESULT_OK, new Intent().putExtra(PlacePicker.EXTRA_PLACE, (Parcelable) place));
    finish();
  }

  @Override
  protected void onResume() {
    super.onResume();

    viewModel.observe(this, searchAdapter::submitList, this::returnPlace, this::handleError);

    disposables = new CompositeDisposable(playServices.checkMaps(this));

    disposables.add(
        searchSubject
            .debounce(SEARCH_DEBOUNCE_TIMEOUT, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(query -> viewModel.query(query, mapPosition)));

    locationDao.getPlaceUsage().observe(this, this::updatePlaces);
  }

  private void handleError(Event<String> error) {
    String message = error.getIfUnhandled();
    if (!Strings.isNullOrEmpty(message)) {
      toaster.longToast(message);
    }
  }

  private void updatePlaces(List<PlaceUsage> places) {
    this.places = places;
    updateMarkers();
    recentsAdapter.submitList(places);
    updateAppbarLayout();
    if (places.isEmpty()) {
      collapseToolbar();
    }
  }

  private void updateMarkers() {
    if (map != null) {
      map.setMarkers(newArrayList(transform(places, PlaceUsage::getPlace)));
    }
  }

  private void updateAppbarLayout() {
    CoordinatorLayout.LayoutParams params =
        (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();

    if (places.isEmpty()) {
      params.height = coordinatorLayout.getHeight();
      chooseRecentLocation.setVisibility(View.GONE);
    } else {
      params.height = (coordinatorLayout.getHeight() * 75) / 100;
      chooseRecentLocation.setVisibility(View.VISIBLE);
    }
  }

  private void collapseToolbar() {
    appBarLayout.setExpanded(true, true);
  }

  private void expandToolbar(boolean animate) {
    appBarLayout.setExpanded(false, animate);
  }

  @Override
  protected void onPause() {
    super.onPause();

    disposables.dispose();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putParcelable(EXTRA_MAP_POSITION, map.getMapPosition());
    outState.putInt(EXTRA_APPBAR_OFFSET, offset);
    viewModel.saveState(outState);
  }

  @Override
  public void inject(ActivityComponent component) {
    component.inject(this);
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    if (item.getItemId() == R.id.menu_search) {
      searchPlace();
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void picked(org.tasks.data.Place place) {
    returnPlace(place);
  }

  @Override
  public void delete(org.tasks.data.Place place) {
    locationDao.delete(place);
  }

  @Override
  public boolean onQueryTextSubmit(String query) {
    return false;
  }

  @Override
  public boolean onQueryTextChange(String query) {
    searchSubject.onNext(query);
    return true;
  }

  @Override
  public void picked(PlaceSearchResult prediction) {
    viewModel.fetch(prediction);
  }

  @Override
  public boolean onMenuItemActionExpand(MenuItem item) {
    searchAdapter.submitList(Collections.emptyList());
    recyclerView.setAdapter(searchAdapter);
    return true;
  }

  @Override
  public boolean onMenuItemActionCollapse(MenuItem item) {
    recyclerView.setAdapter(recentsAdapter);
    if (places.isEmpty()) {
      collapseToolbar();
    }
    return true;
  }
}
