package trades.authz_test

import data.trades.authz

# Run these with: opa test policies/ -v

test_banker_sees_own_desk if {
	authz.allow with input as {
		"subject": {"role": "banker", "desk_id": "FX-NY"},
		"resource": {"desk_id": "FX-NY", "country_code": "US", "owner_id": "u1"},
	}
}

test_banker_does_not_see_other_desk if {
	not authz.allow with input as {
		"subject": {"role": "banker", "desk_id": "FX-NY"},
		"resource": {"desk_id": "FX-LDN", "country_code": "GB", "owner_id": "u1"},
	}
}

test_region_head_sees_country_in_region if {
	authz.allow with input as {
		"subject": {"role": "region_head", "region": "americas"},
		"resource": {"desk_id": "FX-SP", "country_code": "BR", "owner_id": "u9"},
	}
}

test_region_head_does_not_see_country_outside_region if {
	not authz.allow with input as {
		"subject": {"role": "region_head", "region": "americas"},
		"resource": {"desk_id": "FX-LDN", "country_code": "GB", "owner_id": "u9"},
	}
}

test_business_manager_sees_team_members_trades if {
	authz.allow with input as {
		"subject": {"role": "business_manager", "team_member_ids": ["p101", "p102"]},
		"resource": {"desk_id": "FX-NY", "country_code": "US", "owner_id": "p101"},
	}
}

test_business_manager_does_not_see_non_team_trade if {
	not authz.allow with input as {
		"subject": {"role": "business_manager", "team_member_ids": ["p101", "p102"]},
		"resource": {"desk_id": "FX-NY", "country_code": "US", "owner_id": "p999"},
	}
}

test_unknown_role_denied_by_default if {
	not authz.allow with input as {
		"subject": {"role": "intern", "desk_id": "FX-NY"},
		"resource": {"desk_id": "FX-NY", "country_code": "US", "owner_id": "u1"},
	}
}

test_reasons_explain_which_rule_matched if {
	reasons := authz.reasons with input as {
		"subject": {"role": "banker", "desk_id": "FX-NY"},
		"resource": {"desk_id": "FX-NY", "country_code": "US", "owner_id": "u1"},
	}
	reasons == {"desk_match"}
}
