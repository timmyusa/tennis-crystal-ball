package org.strangeforest.tcb.dataload

import org.strangeforest.tcb.util.LockManager

import java.time.temporal.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

import groovy.sql.*

import static java.lang.Math.*
import static org.strangeforest.tcb.util.DateUtil.*

class EloRatings {

	private final SqlPool sqlPool
	private final LockManager<Integer> lockManager
	private Map<Integer, EloRating> playerRatings
	private Date lastDate
	private AtomicInteger saves
	private AtomicInteger progress
	private ExecutorService workerExecutor, saveExecutor
	private Date saveFromDate

	private static final String QUERY_MATCHES = //language=SQL
		"SELECT m.winner_id, m.loser_id, tournament_end(e.date, e.level, e.draw_size) AS end_date, e.level, m.round, m.best_of, m.outcome\n" +
		"FROM match m\n" +
		"INNER JOIN tournament_event e USING (tournament_event_id)\n" +
		"WHERE e.level IN ('G', 'F', 'M', 'O', 'A', 'B', 'D', 'T')\n" +
		"AND (m.outcome IS NULL OR m.outcome <> 'ABD')\n" +
		"ORDER BY end_date, m.round, m.winner_id, m.match_num"

	private static final String QUERY_LAST_DATE = //language=SQL
		"SELECT max(rank_date) AS last_date FROM player_elo_ranking"

	private static final String QUERY_PLAYER_RANK = //language=SQL
		"{? = call player_rank(?, ?)}"

	private static final String MERGE_ELO_RANKING = //language=SQL
		"{call merge_elo_ranking(:rank_date, :player_id, :rank, :elo_rating)}"

	private static final int MIN_MATCHES = 10

	private static final double WRITE_READ_RATIO = 2.0
	private static final int MATCHES_PER_DOT = 1000
	private static final int SAVES_PER_PLUS = 20
	private static final int PROGRESS_LINE_WRAP = 100
	private static final int PLAYERS_TO_SAVE = 200

	private static def comparator = { a, b -> b.value <=> a.value }
	private static def bestComparator = { a, b -> b.value.bestRating <=> a.value.bestRating }

	EloRatings(SqlPool sqlPool) {
		this.sqlPool = sqlPool
		lockManager = new LockManager<>()
	}

	def compute(save = false, deltaSave = false, saveFromDate = null) {
		println 'Processing matches'
		def t0 = System.currentTimeMillis()
		int matches = 0
		List<Future> matchFutures = new ArrayList<>()
		playerRatings = [:]
		lastDate = null
		saves = new AtomicInteger()
		progress = new AtomicInteger()
		def remainingPoolSize = sqlPool.size() - 1
		int saveThreads = save ? remainingPoolSize * WRITE_READ_RATIO / (1 + WRITE_READ_RATIO) : 0
		int workerThreads = max(remainingPoolSize - saveThreads, 1)
		println "Using $workerThreads worker threads"
		workerExecutor = Executors.newFixedThreadPool(workerThreads)
		if (save) {
			saveExecutor = Executors.newFixedThreadPool(saveThreads)
			println "Using $saveThreads saving threads"
			if (deltaSave && !saveFromDate) {
				sqlPool.withSql { sql ->
					saveFromDate = sql.firstRow(QUERY_LAST_DATE).last_date
				}
			}
			this.saveFromDate = saveFromDate
		}
		sqlPool.withSql { sql ->
			try {
				sql.eachRow(QUERY_MATCHES) { match ->
					def date = match.end_date
					if (date != lastDate) {
						waitForAllMatchesToComplete(matchFutures)
						saveCurrentRatings()
					}
					def winnerId = match.winner_id
					def loserId = match.loser_id
					def level = match.level
					def round = match.round
					def bestOf = match.best_of
					def outcome = match.outcome
					matchFutures.add workerExecutor.submit {
						processMatch(date, winnerId, loserId, level, round, bestOf, outcome)
					}
					lastDate = date
					if (++matches % MATCHES_PER_DOT == 0)
						progressTick '.'
				}
				waitForAllMatchesToComplete(matchFutures)
				saveCurrentRatings()
			}
			finally {
				workerExecutor.shutdownNow()
				saveExecutor?.shutdown()
				saveExecutor?.awaitTermination(1L, TimeUnit.DAYS)
			}
		}
		def seconds = (System.currentTimeMillis() - t0) / 1000.0
		println "\nElo Ratings computed in $seconds s"
		playerRatings
	}

	static def waitForAllMatchesToComplete(def matchFutures) {
		matchFutures.forEach { it -> it.get() }
		matchFutures.clear()
	}

	def current(int count, Date date = new Date()) {
		Date minDate = toDate(toLocalDate(date).minusYears(1))
		def i = 0
		playerRatings.findAll {	it.value.matches >= MIN_MATCHES && it.value.date >= minDate	}
			.sort(comparator)
			.findAll { ++i <= count }
	}

	def allTime(int count) {
		def i = 0
		playerRatings.findAll {	it.value.bestRating }
			.sort(bestComparator)
			.findAll { ++i <= count }
	}

	def processMatch(date, winnerId, loserId, level, round, bestOf, outcome) {
		lockManager.withLock(min(winnerId, loserId)) {
			lockManager.withLock(max(winnerId, loserId)) {
				def winnerRating = getRating(winnerId)
				def loserRating = getRating(loserId)

				double winnerQ = pow(10, winnerRating.rating / 400)
				double loserQ = pow(10, loserRating.rating / 400)
				double loserExpectedScore = loserQ / (winnerQ + loserQ)

				double deltaRating = kFactor(level, round, bestOf, outcome) * loserExpectedScore

				playerRatings.put(winnerId, winnerRating.newRating(deltaRating, date))
				playerRatings.put(loserId, loserRating.newRating(-deltaRating, date))
			}
		}
	}

	private EloRating getRating(int playerId) {
		playerRatings.get(playerId) ?: new EloRating(playerRank(playerId, lastDate))
	}

	private Integer playerRank(int playerId, Date date) {
		Integer playerRank
		sqlPool.withSql { sql ->
			sql.call(QUERY_PLAYER_RANK, [Sql.INTEGER, playerId, date]) { rank -> playerRank = rank }
		}
		playerRank
	}

	static double kFactor(level, round, bestOf, outcome) {
		double kFactor = 100
		switch (level) {
			case 'G': break
			case 'F': kFactor *= 0.9; break
			case 'M': kFactor *= 0.8; break
			case 'A': kFactor *= 0.7; break
			default: kFactor *= 0.6; break
		}
		switch (round) {
			case 'F': break
			case 'BR': kFactor *= 0.975; break
			case 'SF': kFactor *= 0.95; break
			case 'QF': kFactor *= 0.90; break
			case 'R16': kFactor *= 0.85; break
			case 'R32': kFactor *= 0.80; break
			case 'R64': kFactor *= 0.75; break
			case 'R128': kFactor *= 0.70; break
			case 'RR': kFactor *= 0.90; break
		}
		if (bestOf < 5) kFactor *= 0.9
		if (outcome == 'W/O') kFactor *= 0.5
		kFactor
	}

	static class EloRating implements Comparable<EloRating> {

		double rating
		int matches
		Date date
		EloRating bestRating

		private static final START_RATING_TABLE = [
			ratingPoint(1, 2365),
			ratingPoint(2, 2290),
			ratingPoint(3, 2235),
			ratingPoint(4, 2195),
			ratingPoint(5, 2160),
			ratingPoint(7, 2110),
			ratingPoint(10, 2060),
			ratingPoint(15, 2015),
			ratingPoint(20, 1980),
			ratingPoint(30, 1925),
			ratingPoint(50, 1840),
			ratingPoint(70, 1770),
			ratingPoint(100, 1695),
			ratingPoint(150, 1615),
			ratingPoint(200, 1555),
			ratingPoint(300, 1500)
		]
		static final int START_RATING = START_RATING_TABLE[START_RATING_TABLE.size() - 1].eloRating

		EloRating() {}

		EloRating(Integer rank) {
			rating = startRating(rank)
		}

		EloRating newRating(double delta, Date date) {
			def newRating = new EloRating(rating: ratingDateAdjusted(date) + delta * kFunction(), matches: matches + 1, date: date)
			newRating.bestRating = bestRating(newRating)
			newRating
		}

		def ratingDateAdjusted(Date date) {
			if (this.date) {
				def daysSinceLastMatch = ChronoUnit.DAYS.between(toLocalDate(this.date), toLocalDate(date))
				if (daysSinceLastMatch > 365)
					return max(START_RATING, rating - (daysSinceLastMatch - 365) * 200 / 365)
			}
			rating
		}

		def bestRating(EloRating newRating) {
			if (matches >= MIN_MATCHES)
				bestRating && bestRating >= newRating ? bestRating : newRating
			else
				null
		}

		/**
		 * K-Function returns values from 1/2 to 1.
		 * For rating 0-1800 returns 1
		 * For rating 1800-2000 returns linearly decreased values from 1 to 1/2. For example, for 1900 return 3/4
		 * For rating 2000+ returns 1/2
		 * @return values from 1/2 to 1, depending on current rating
		 */
		private def double kFunction() {
			if (rating <= 1800)
				1.0
			else if (rating <= 2000)
				1.0 - (rating - 1800) / 400.0
			else
				0.5
		}

		private static def ratingPoint(int rank, int eloRating) {
			return new RatingPoint(rank: rank, eloRating: eloRating)
		}

		static int startRating(Integer rank) {
			if (rank) {
				RatingPoint prevPoint
				for (RatingPoint point : START_RATING_TABLE) {
					if (rank == point.rank)
						return point.eloRating
					else if (rank < point.rank) {
						if (prevPoint != null)
							return prevPoint.eloRating - (prevPoint.eloRating - point.eloRating) * (rank - prevPoint.rank) / (point.rank - prevPoint.rank)
						else
							return point.eloRating
					}
					prevPoint = point
				}
			}
			START_RATING
		}

		static class RatingPoint {
			int rank;
			int eloRating;
		}

		String toString() {
			round rating
		}

		int compareTo(EloRating eloRating) {
			rating <=> eloRating.rating
		}
	}


	def saveCurrentRatings() {
		if (saveExecutor && playerRatings && (!saveFromDate || lastDate >= saveFromDate)) {
			def ratingsToSave = current(PLAYERS_TO_SAVE, lastDate).collectEntries { k, v -> [k, v.rating] }
			def dateToSave = lastDate
			saveExecutor.execute { saveRatings(ratingsToSave, dateToSave) }
		}
	}

	def saveRatings(Map<Integer, Double> ratings, Date date) {
		sqlPool.withSql { sql ->
			sql.withBatch(MERGE_ELO_RANKING) { ps ->
				def i = 0
				ratings.each { it ->
					Map params = [:]
					params.rank_date = new java.sql.Date(date.time)
					params.player_id = it.key
					params.rank = ++i
					params.elo_rating = (int)round(it.value)
					ps.addBatch(params)
				}
			}
		}
		if (saves.incrementAndGet() % SAVES_PER_PLUS == 0)
			progressTick '+'
	}

	private progressTick(tick) {
		print tick
		if (progress.incrementAndGet() % PROGRESS_LINE_WRAP == 0)
			println()
	}
}
