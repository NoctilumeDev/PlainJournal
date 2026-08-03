import type { ApiClient, BusinessId } from "./api";

export interface UserProfile {
  id: BusinessId;
  email: string;
  displayName: string;
  status: string;
  roles: string[];
}

export interface AuthTokens {
  tokenType: "Bearer" | string;
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
}

export interface Address {
  id: BusinessId;
  recipientName: string;
  phone: string;
  province: string;
  provinceCode: string;
  city: string;
  cityCode: string;
  district: string;
  districtCode: string;
  detailAddress: string;
  postalCode: string | null;
  defaultAddress: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface RegisterInput {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

export interface AddressInput {
  recipientName: string;
  phone: string;
  province: string;
  provinceCode: string;
  city: string;
  cityCode: string;
  district: string;
  districtCode: string;
  detailAddress: string;
  postalCode: string | null;
  setDefault: boolean;
}

export interface IdentityApi {
  register(input: RegisterInput): Promise<UserProfile>;
  login(input: LoginInput): Promise<AuthTokens>;
  refresh(refreshToken: string): Promise<AuthTokens>;
  logout(refreshToken: string): Promise<void>;
  currentUser(): Promise<UserProfile>;
  addresses(): Promise<Address[]>;
  createAddress(input: AddressInput): Promise<Address>;
  updateAddress(addressId: BusinessId, input: AddressInput): Promise<Address>;
  setDefaultAddress(addressId: BusinessId): Promise<Address>;
  deleteAddress(addressId: BusinessId): Promise<void>;
}

export function createIdentityApi(client: ApiClient): IdentityApi {
  return {
    register(input) {
      return client.request<UserProfile>("/api/v1/identity/auth/register", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    login(input) {
      return client.request<AuthTokens>("/api/v1/identity/auth/login", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    refresh(refreshToken) {
      return client.request<AuthTokens>("/api/v1/identity/auth/refresh", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      });
    },
    logout(refreshToken) {
      return client.request<void>("/api/v1/identity/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      });
    },
    currentUser() {
      return client.request<UserProfile>("/api/v1/identity/me");
    },
    addresses() {
      return client.request<Address[]>("/api/v1/identity/addresses");
    },
    createAddress(input) {
      return client.request<Address>("/api/v1/identity/addresses", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    updateAddress(addressId, input) {
      return client.request<Address>(
        `/api/v1/identity/addresses/${encodeURIComponent(addressId)}`,
        {
          method: "PUT",
          body: JSON.stringify(input),
        },
      );
    },
    setDefaultAddress(addressId) {
      return client.request<Address>(
        `/api/v1/identity/addresses/${encodeURIComponent(addressId)}/default`,
        { method: "POST" },
      );
    },
    deleteAddress(addressId) {
      return client.request<void>(
        `/api/v1/identity/addresses/${encodeURIComponent(addressId)}`,
        { method: "DELETE" },
      );
    },
  };
}
