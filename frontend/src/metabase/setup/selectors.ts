import { createSelector } from "@reduxjs/toolkit";

import { isEEBuild } from "metabase/lib/utils";
import { getSetting } from "metabase/selectors/settings";
import type {
  DatabaseData,
  LocaleData,
  TokenFeature,
} from "metabase-types/api";
import type { InviteInfo, Locale, State, UserInfo } from "metabase-types/store";

import { isNotFalsy } from "./../lib/types";
import type { SetupStep } from "./types";

const DEFAULT_LOCALES: LocaleData[] = [];

export const getStep = (state: State): SetupStep => {
  return state.setup.step;
};

export const getLocale = (state: State): Locale | undefined => {
  return state.setup.locale;
};

export const getUser = (state: State): UserInfo | undefined => {
  return state.setup.user;
};

export const getUserEmail = (state: State): string | undefined => {
  return getUser(state)?.email;
};

export const getUsageReason = (state: State) => {
  return state.setup.usageReason;
};

export const getDatabase = (state: State): DatabaseData | undefined => {
  return state.setup.database;
};

export const getInvite = (state: State): InviteInfo | undefined => {
  return state.setup.invite;
};

export const getIsLocaleLoaded = (state: State): boolean => {
  return state.setup.isLocaleLoaded;
};

export const getIsTrackingAllowed = (state: State): boolean => {
  return state.setup.isTrackingAllowed;
};

export const getIsStepActive = (state: State, step: SetupStep): boolean => {
  return getStep(state) === step;
};

export const getIsStepCompleted = (state: State, step: SetupStep): boolean => {
  const steps = getSteps(state);
  return (
    steps.findIndex((s) => s.key === step) <
    steps.findIndex((s) => s.isActiveStep)
  );
};

export const getIsSetupCompleted = (state: State): boolean => {
  return getStep(state) === "completed";
};

export const getDatabaseEngine = (state: State): string | undefined => {
  return getDatabase(state)?.engine || state.setup.databaseEngine;
};

export const getSetupToken = (state: State) => {
  return getSetting(state, "setup-token");
};

export const getIsHosted = (state: State): boolean => {
  return getSetting(state, "is-hosted?");
};

export const getTokenFeature = (state: State, feature: TokenFeature) => {
  const tokenFeatures = getSetting(state, "token-features");
  return tokenFeatures[feature];
};

export const getAvailableLocales = (state: State): LocaleData[] => {
  return getSetting(state, "available-locales") ?? DEFAULT_LOCALES;
};

export const getIsEmailConfigured = (state: State): boolean => {
  return getSetting(state, "email-configured?");
};

export const getSteps = createSelector(
  [
    (state: State) => getUsageReason(state),
    (state: State) => getStep(state),
    (state: State) => getSetting(state, "token-features"),
    (state: State) => state.setup.licenseToken,
  ],
  (usageReason, activeStep, tokenFeatures, licenseToken) => {
    const isPaidPlan =
      tokenFeatures &&
      Object.values(tokenFeatures).some((value) => value === true);
    const hasAddedPaidPlanInPreviousStep = Boolean(licenseToken);

    const shouldShowDBConnectionStep = usageReason !== "embedding";
    const shouldShowLicenseStep =
      isEEBuild() && (!isPaidPlan || hasAddedPaidPlanInPreviousStep);

    // note: when hosting is true, we should be on cloud and therefore not show
    // the token step. There is an edge case that it's probably not possible in
    // real life: somebody submitting a key with the hosting feature in the
    // token step. This happens in our e2e test where we use the NO_FEATURES
    // token which actually has the hosting feature so I added the
    // hasAddedPaidPlanInPreviousStep check
    const isHosted =
      tokenFeatures &&
      tokenFeatures["hosting"] &&
      !hasAddedPaidPlanInPreviousStep;
    const shouldShowDataUsageStep = !isHosted;

    const steps: { key: SetupStep; isActiveStep: boolean }[] = [
      { key: "welcome" as const },
      { key: "language" as const },
      { key: "user_info" as const },
      { key: "usage_question" as const },
      shouldShowDBConnectionStep && {
        key: "db_connection" as const,
      },
      shouldShowLicenseStep && { key: "license_token" as const },
      shouldShowDataUsageStep ? { key: "data_usage" as const } : null,
      { key: "completed" as const },
    ]
      .filter(isNotFalsy)
      .map(({ key }) => ({
        key,
        isActiveStep: activeStep === key,
      }));

    return steps;
  },
);

export const getNextStep = (state: State) => {
  const steps = getSteps(state);
  const activeStepIndex = steps.findIndex((step) => step.isActiveStep);
  return steps[activeStepIndex + 1].key;
};
