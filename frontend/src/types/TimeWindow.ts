export type TimeWindow = {
  windowId: string;
  deviceId: string;
  deviceType: string;
  from: string;     // "18:00"
  to: string;       // "22:00"
  timezone: string;
  enabled: boolean;
};

export type CreateTimeWindowRequest = {
  deviceId: string;
  deviceType: string;
  from: string;
  to: string;
  timezone?: string;
};
