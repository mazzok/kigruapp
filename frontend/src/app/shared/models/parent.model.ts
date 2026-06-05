export interface Address {
  street: string;
  zip: string;
  city: string;
}

export interface Parent {
  id?: string;
  familyId: string;
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  address?: Address;
  keycloakUserId?: string;
  permissions?: string[];
  customFields?: Record<string, unknown>;
}
