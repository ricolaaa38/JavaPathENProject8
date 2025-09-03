package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {

    private static final int NEARBY_ATTRACTIONS_LIMIT = 5;
    private static final int TRIP_DEAL_COUNT = 10;

	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;
    
    // Executor service to track user locations concurrently
    private final ExecutorService trackingExecutor;

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

        // Adjust the number of threads based on the number of users
        int users = InternalTestHelper.getInternalUserNumber();
        int threads = computeIoThreads(users);
        this.trackingExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("tg-io-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        logger.info("Tracking executor size: {}", threads);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

    // Compute optimal number of I/O threads based on users and CPU cores
    private int computeIoThreads(int users) {
        int cpu = Runtime.getRuntime().availableProcessors();
        int ioHint = Math.max(cpu * 8, 32);     // base I/O
        int byUsers = Math.min(users, 512);     // borne haute raisonnable
        return Math.max(2, Math.max(ioHint, byUsers));
    }

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
        return (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation()
                : trackUserLocation(user);
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	public void addUser(User user) {
		internalUserMap.putIfAbsent(user.getUserName(), user);
	}

	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(UserReward::getRewardPoints).sum();
		List<Provider> providers = new ArrayList<>();
        while (providers.size() < TRIP_DEAL_COUNT) {
            providers.addAll(tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
                    user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
                    user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints));
        }
        if (providers.size() > TRIP_DEAL_COUNT) {
            providers = providers.subList(0, TRIP_DEAL_COUNT);
        }
		user.setTripDeals(providers);
		return providers;
	}

    // Track user location and add it to their visited locations
	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		return visitedLocation;
	}

    // Track user location and calculate rewards concurrently
    public void trackUserLocationAndCalculateRewards(User user) {
        trackUserLocation(user);
        rewardsService.calculateRewards(user);
    }

    public List<VisitedLocation> trackAllUserLocationAsync(Collection<User> users) {
        List<CompletableFuture<VisitedLocation>> futures = users.stream()
                .map(user -> CompletableFuture.supplyAsync(() -> trackUserLocation(user), trackingExecutor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    public void trackAllUsersAndCalculateRewardsAsync(Collection<User> users) {
        List<CompletableFuture<Void>> futures = users.stream()
                .map(user -> CompletableFuture.runAsync(() -> trackUserLocationAndCalculateRewards(user), trackingExecutor))
                .toList();
        futures.forEach(CompletableFuture::join);
    }

    public void calculateRewardsForAllUsersAsync(Collection<User> users) {
        List<CompletableFuture<Void>> futures = users.stream()
                .map(user -> CompletableFuture.runAsync(() -> rewardsService.calculateRewards(user), trackingExecutor))
                .toList();
        futures.forEach(CompletableFuture::join);
    }

    public List<gpsUtil.location.Attraction> getNearByAttractions(gpsUtil.location.VisitedLocation visitedLocation) {
        return gpsUtil.getAttractions().stream()
                .sorted(java.util.Comparator.comparingDouble(a -> rewardsService.getDistance(a, visitedLocation.location)))
                .limit(NEARBY_ATTRACTIONS_LIMIT)
                .collect(java.util.stream.Collectors.toList());
    }

    public List<NearbyAttractionDTO> getNearByAttractionsDetails(VisitedLocation visitedLocation) {
        Location userLocation = visitedLocation.location;
        UUID userId = visitedLocation.userId;
        List<NearbyAttractionDTO> result = gpsUtil.getAttractions().stream()
                .sorted(Comparator.comparingDouble(a -> rewardsService.getDistance(a, userLocation)))
                .limit(NEARBY_ATTRACTIONS_LIMIT)
                .map(a -> {
                    double distance = rewardsService.getDistance(a, userLocation);
                    int points = rewardsService.getRewardPointsForAttraction(a, userId);
                    return new NearbyAttractionDTO(
                            a.attractionName,
                            a.latitude,
                            a.longitude,
                            userLocation.latitude,
                            userLocation.longitude,
                            distance,
                            points
                    );
                })
                .collect(Collectors.toList());

        logger.info("Nearby attractions for userId {} at [{}, {}]: {}",
                userId, userLocation.latitude, userLocation.longitude, result);

        return result;
    }


	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook( new Thread(() -> {
            tracker.stopTracking();
            trackingExecutor.shutdownNow();
        }));
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
