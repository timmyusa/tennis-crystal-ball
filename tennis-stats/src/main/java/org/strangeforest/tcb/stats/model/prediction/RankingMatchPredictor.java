package org.strangeforest.tcb.stats.model.prediction;

import java.util.function.*;

import org.strangeforest.tcb.stats.model.elo.*;

import static java.lang.Math.*;
import static org.strangeforest.tcb.stats.model.prediction.MatchDataUtil.*;
import static org.strangeforest.tcb.stats.model.prediction.RankingPredictionItem.*;

public class RankingMatchPredictor implements MatchPredictor {

	private final RankingData rankingData1;
	private final RankingData rankingData2;
	private final short bestOf;
	private final PredictionConfig config;

	private static final int DEFAULT_RANK = 500;
	private static final int DEFAULT_RANK_POINTS = 10;
	// Experimentally determined for smallest Brier score and calibration near to 1 for seasons 2005+
	private static final double RANK_BEST_OF_3_PROBABILITY_EXPONENT = 0.6;
	private static final double RANK_BEST_OF_5_PROBABILITY_EXPONENT = 0.87;
	private static final double RANK_POINTS_BEST_OF_3_PROBABILITY_EXPONENT = 0.77;
	private static final double RANK_POINTS_BEST_OF_5_PROBABILITY_EXPONENT = 1.02;

	public RankingMatchPredictor(RankingData rankingData1, RankingData rankingData2, short bestOf, PredictionConfig config) {
		this.rankingData1 = rankingData1;
		this.rankingData2 = rankingData2;
		this.bestOf = bestOf;
		this.config = config;
	}

	@Override public PredictionArea getArea() {
		return PredictionArea.RANKING;
	}

	@Override public MatchPrediction predictMatch() {
		MatchPrediction prediction = new MatchPrediction(config.getTotalAreasWeight(), bestOf);
		addRankItemProbabilities(prediction, RANK, rankingData1.getRank(), rankingData2.getRank());
		addRankPointsItemProbabilities(prediction, RANK_POINTS, rankingData1.getRankPoints(), rankingData2.getRankPoints());
		addEloItemProbabilities(prediction, ELO, rankingData1.getEloRating(), rankingData2.getEloRating());
		addEloItemProbabilities(prediction, RECENT_ELO, rankingData1.getRecentEloRating(), rankingData2.getRecentEloRating());
		addEloItemProbabilities(prediction, SURFACE_ELO, rankingData1.getSurfaceEloRating(), rankingData2.getSurfaceEloRating());
		addEloItemProbabilities(prediction, IN_OUT_ELO, rankingData1.getInOutEloRating(), rankingData2.getInOutEloRating());
		addEloItemProbabilities(prediction, SET_ELO, rankingData1.getSetEloRating(), rankingData2.getSetEloRating());
		return prediction;
	}


	// Rank

	private void addRankItemProbabilities(MatchPrediction prediction, RankingPredictionItem item, Integer rank1, Integer rank2) {
		double weight = config.getItemWeight(item) * presenceWeight(rank1, rank2);
		if (weight > 0.0) {
			prediction.addItemProbability1(item, weight, rankWinProbability(rank1, rank2));
			prediction.addItemProbability2(item, weight, rankWinProbability(rank2, rank1));
		}
	}

	private double rankWinProbability(Integer rank1, Integer rank2) {
		rank1 = defaultIfNull(rank1, DEFAULT_RANK);
		rank2 = defaultIfNull(rank2, DEFAULT_RANK);
		return 1 / (1 + pow((double)rank1 / rank2, bestOf < 5 ? RANK_BEST_OF_3_PROBABILITY_EXPONENT : RANK_BEST_OF_5_PROBABILITY_EXPONENT));
	}


	// Rank Points

	private void addRankPointsItemProbabilities(MatchPrediction prediction, RankingPredictionItem item, Integer rankPoints1, Integer rankPoints2) {
		double weight = config.getItemWeight(item) * presenceWeight(rankPoints1, rankPoints2);
		if (weight > 0.0) {
			prediction.addItemProbability1(item, weight, rankPointsWinProbability(rankPoints1, rankPoints2));
			prediction.addItemProbability2(item, weight, rankPointsWinProbability(rankPoints2, rankPoints1));
		}
	}

	private double rankPointsWinProbability(Integer rankPoints1, Integer rankPoints2) {
		rankPoints1 = defaultIfNull(rankPoints1, DEFAULT_RANK_POINTS);
		rankPoints2 = defaultIfNull(rankPoints2, DEFAULT_RANK_POINTS);
		return 1 / (1 + pow((double)rankPoints2 / rankPoints1, bestOf < 5 ? RANK_POINTS_BEST_OF_3_PROBABILITY_EXPONENT : RANK_POINTS_BEST_OF_5_PROBABILITY_EXPONENT));
	}


	// Elo

	private void addEloItemProbabilities(MatchPrediction prediction, RankingPredictionItem item, Integer eloRating1, Integer eloRating2) {
		double weight = config.getItemWeight(item) * presenceWeight(eloRating1, eloRating2);
		if (weight > 0.0) {
			DoubleUnaryOperator probabilityTransformer = probabilityTransformer(item.isForSet(), true, bestOf);
			prediction.addItemProbability1(item, weight, probabilityTransformer.applyAsDouble(eloWinProbability(eloRating1, eloRating2)));
			prediction.addItemProbability2(item, weight, probabilityTransformer.applyAsDouble(eloWinProbability(eloRating2, eloRating1)));
		}
	}

	private double eloWinProbability(Integer eloRating1, Integer eloRating2) {
		eloRating1 = defaultIfNull(eloRating1, (int)StartEloRatings.START_RATING);
		eloRating2 = defaultIfNull(eloRating2, (int)StartEloRatings.START_RATING);
		return EloCalculator.eloWinProbability(eloRating1, eloRating2);
	}


	// Util

	private static double presenceWeight(Integer value1, Integer value2) {
		boolean present1 = isPresent(value1);
		boolean present2 = isPresent(value2);
		if (present1 && present2)
			return 1.0;
		else if (!present1 && !present2)
			return 0.0;
		else
			return 0.5;
	}

	private static Integer defaultIfNull(Integer value, Integer defaultValue) {
		return isPresent(value) ? value : defaultValue;
	}

	private static boolean isPresent(Integer value) {
		return value != null && value != 0;
	}
}
