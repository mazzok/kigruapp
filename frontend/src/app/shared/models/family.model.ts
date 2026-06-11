export interface FamilyAddress {
  street: string;
  zip: string;
  city: string;
}

export interface Family {
  id?: string;
  name: string;
  address?: FamilyAddress;
  createdAt?: string;
}
